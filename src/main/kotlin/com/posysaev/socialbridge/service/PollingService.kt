package com.posysaev.socialbridge.service

import com.posysaev.socialbridge.client.telegram.TelegramClient
import com.posysaev.socialbridge.client.vkontakte.VkClient
import com.posysaev.socialbridge.config.TelegramProperties
import com.posysaev.socialbridge.dto.telegram.AlbumBuf
import com.posysaev.socialbridge.dto.telegram.TgMessageEntity
import com.posysaev.socialbridge.dto.telegram.TgUpdate
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Основной сервис для периодического опроса Telegram-канала и пересылки постов во ВКонтакте.
 *
 * Поддерживает:
 *  - обычные текстовые сообщения;
 *  - сообщения с одной фотографией;
 *  - альбомы (media_group) из нескольких фото.
 *
 * Альбомы из нескольких сообщений Telegram собираются во временный буфер [AlbumBuf],
 * а затем публикуются как один пост во ВКонтакте.
 */
@Service
@EnableScheduling
class PollingService(
    private val tg: TelegramClient,
    private val vk: VkClient,
    private val tgProps: TelegramProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lastUpdateId = AtomicLong(0L)
    private val processedMessages = mutableSetOf<String>()

    /** Буфер для альбомов: ключ — media_group_id, значение — временное хранилище элементов альбома */
    private val albums = mutableMapOf<String, AlbumBuf>()

    /** Время ожидания (в мс) после первой части альбома перед публикацией */
    private val albumBufferMs = 2500L

    /**
     * Планировщик, который регулярно опрашивает Telegram API на наличие новых сообщений.
     *
     * @Scheduled запускает метод с указанным интервалом, заданным в настройке
     * `telegram.polling-interval-ms` (по умолчанию 5 секунд).
     *
     * Каждый апдейт проверяется и обрабатывается методом [processUpdate],
     * а после — вызывается [flushExpiredAlbums] для публикации готовых альбомов.
     */
    @Scheduled(fixedDelayString = "\${telegram.polling-interval-ms:5000}")
    fun poll() {
        val currentOffset = lastUpdateId.get()
        try {
            // Получаем новые обновления из Telegram начиная с последнего ID
            val updates = tg.getUpdates(
                if (currentOffset == 0L) null else currentOffset + 1,
                tgProps.timeoutSec
            )
            if (!updates.ok) return

            // Обрабатываем каждое сообщение
            updates.result.forEach { upd ->
                try {
                    processUpdate(upd)
                    lastUpdateId.updateAndGet { cur -> max(cur, upd.updateId) }
                } catch (e: Exception) {
                    log.error("Failed to process update ${upd.updateId}", e)
                    // Даже при ошибке двигаем offset, чтобы не зациклиться
                    lastUpdateId.updateAndGet { cur -> max(cur, upd.updateId) }
                }
            }

            // После обработки пакета апдейтов публикуем готовые альбомы
            flushExpiredAlbums()
        } catch (e: Exception) {
            log.error("Polling failed", e)
        }
    }

    /**
     * Обработка одного обновления Telegram ([TgUpdate]).
     *
     * - Отбрасывает дубликаты и неразрешённые чаты.
     * - Распознаёт тип сообщения (текст / фото / альбом).
     * - Вызывает публикацию в VK через [VkClient].
     */
    private fun processUpdate(upd: TgUpdate) {
        val msg = upd.channelPost ?: upd.message ?: return

        // Проверка: сообщение должно быть из разрешённого списка каналов
        if (msg.chat.id !in tgProps.sourceChatIds) return

        // Проверка на дубли (чтобы не постить одно и то же)
        val key = "${msg.chat.id}:${msg.messageId}"
        if (!processedMessages.add(key)) return

        // Извлекаем текст и сущности (ссылки, форматирование)
        val (rawText, ents) = when {
            msg.text != null -> msg.text to (msg.entities ?: emptyList())
            msg.caption != null -> msg.caption to (msg.captionEntities ?: emptyList())
            else -> null to emptyList()
        }
        val text = expandTextLinks(rawText, ents)

        // === Если это часть альбома (media_group) ===
        msg.mediaGroupId?.let { gid ->
            val best = msg.photo?.maxByOrNull { it.width * it.height } ?: return
            val album = albums.getOrPut(gid) {
                AlbumBuf(msg.chat.id, System.currentTimeMillis())
            }
            // добавляем фото и подпись (caption, если она есть)
            album.items += best.fileId to (msg.caption ?: album.items.firstOrNull()?.second)
            if (!msg.captionEntities.isNullOrEmpty()) album.captionEntities = msg.captionEntities!!
            return // не постим сразу, ждём остальные части альбома
        }

        // === Обычное сообщение (одиночное фото или текст) ===
        val photo = msg.photo?.maxByOrNull { it.width * it.height }
        if (photo != null) {
            // скачиваем фото и публикуем
            val fileInfo = tg.getFile(photo.fileId)
            val path = fileInfo.result.filePath ?: return
            val bytes = tg.downloadFileBytes(path)
            val fileName = path.substringAfterLast('/')
            vk.postToWall(text, listOf(bytes to fileName))
        } else if (!text.isNullOrBlank()) {
            // публикуем только текст
            vk.postToWall(text)
        }
    }

    /**
     * Проверяет, какие альбомы "созрели" (прошло больше [albumBufferMs]),
     * и публикует их во ВКонтакте как один пост.
     *
     * После публикации альбом удаляется из буфера [albums].
     */
    private fun flushExpiredAlbums() {
        val now = System.currentTimeMillis()
        val ready = albums.filterValues { now - it.startedAt >= albumBufferMs }.toList()
        ready.forEach { (groupId, buf) ->
            try {
                // загружаем файлы по их fileId
                val photos = buf.items.map { (fileId, _) ->
                    val path = tg.getFile(fileId).result.filePath ?: error("Null path for $fileId")
                    tg.downloadFileBytes(path) to path.substringAfterLast('/')
                }

                if (photos.isNotEmpty()) {
                    // формируем текст подписи и публикуем
                    val caption = expandTextLinks(buf.items.firstOrNull()?.second, buf.captionEntities)
                    vk.postToWall(caption, photos)
                    log.info("VK: posted album {} ({} photos)", groupId, photos.size)
                }
            } catch (e: Exception) {
                log.error("Failed to post album {}", groupId, e)
            } finally {
                // очищаем буфер альбома
                albums.remove(groupId)
            }
        }
    }

    /**
     * Преобразует Telegram-ссылки (entities с type = text_link) в "видимый текст + URL",
     * чтобы при публикации во ВКонтакте ссылка не терялась.
     *
     * Пример:
     *   Telegram:  [OpenAI](https://openai.com)
     *   Результат: OpenAI https://openai.com
     */
    private fun expandTextLinks(text: String?, entities: List<TgMessageEntity>?): String? {
        if (text.isNullOrEmpty() || entities.isNullOrEmpty()) return text
        val links = entities.filter { it.type == "text_link" && !it.url.isNullOrBlank() }
            .sortedByDescending { it.offset }
        val sb = StringBuilder(text)
        for (e in links) {
            val start = e.offset
            val end = (e.offset + e.length).coerceAtMost(sb.length)
            val visible = sb.substring(start, end)
            sb.replace(start, end, "$visible ${e.url}")
        }
        return sb.toString()
    }
}
