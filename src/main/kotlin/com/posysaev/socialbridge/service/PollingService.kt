package com.posysaev.socialbridge.service

import com.posysaev.socialbridge.client.telegram.TelegramClient
import com.posysaev.socialbridge.client.vkontakte.VkClient
import com.posysaev.socialbridge.config.TelegramProperties
import com.posysaev.socialbridge.dto.telegram.TgMessageEntity
import com.posysaev.socialbridge.dto.telegram.TgUpdate
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

@Service
@EnableScheduling
class PollingService(
    private val tg: TelegramClient,
    private val vk: VkClient,
    private val tgProps: TelegramProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lastUpdateId = AtomicLong(0L)
    private val processedMessages = mutableSetOf<String>() // защита от дубликатов

    @Scheduled(fixedDelayString = "\${telegram.polling-interval-ms:5000}")
    fun poll() {
        val currentOffset = lastUpdateId.get()
        log.debug("Starting poll with offset: {}", if (currentOffset == 0L) "null" else currentOffset + 1)

        try {
            val updates = tg.getUpdates(
                if (currentOffset == 0L) null else currentOffset + 1,
                tgProps.timeoutSec
            )

            if (!updates.ok) {
                log.warn("Telegram API returned ok=false")
                return
            }

            log.debug("Received {} updates", updates.result.size)

            updates.result.forEach { upd ->
                try {
                    processUpdate(upd)
                    // offset двигаем только после успешной обработки
                    lastUpdateId.updateAndGet { current -> max(current, upd.updateId) }
                } catch (e: Exception) {
                    log.error(
                        "Failed to process update {} - SKIPPING and moving offset forward",
                        upd.updateId, e
                    )
                    // ВАЖНО: всё равно обновляем offset, чтобы не застрять
                    lastUpdateId.updateAndGet { current -> max(current, upd.updateId) }
                }
            }

            log.debug("Poll completed. New offset: {}", lastUpdateId.get())

        } catch (e: Exception) {
            log.error("Polling failed at offset ${lastUpdateId.get()}", e)
            // исключение не пробрасываем — Spring попробует снова
        }
    }

    private fun processUpdate(upd: TgUpdate) {
        val msg = upd.channelPost ?: upd.message
        if (msg == null) {
            log.debug("Skip update {} (no message/channelPost)", upd.updateId)
            return
        }

        val messageKey = "${msg.chat.id}:${msg.messageId}"

        log.info(
            "Processing update {} | chatId={} | messageId={} | chatType={}",
            upd.updateId, msg.chat.id, msg.messageId, msg.chat.type
        )

        // Проверка дубликатов
        if (processedMessages.contains(messageKey)) {
            log.warn("DUPLICATE detected! Already processed message: {}", messageKey)
            return
        }

        // Проверка источника
        if (msg.chat.id !in tgProps.sourceChatIds) {
            log.debug("Skip: chatId {} not in sourceChatIds {}", msg.chat.id, tgProps.sourceChatIds)
            return
        }

        val (rawText, ents) = when {
            msg.text != null -> {
                log.debug(
                    "Message has text: {} chars, {} entities",
                    msg.text.length, msg.entities?.size ?: 0
                )
                msg.text to (msg.entities ?: emptyList())
            }

            msg.caption != null -> {
                log.debug(
                    "Message has caption: {} chars, {} entities",
                    msg.caption.length, msg.captionEntities?.size ?: 0
                )
                msg.caption to (msg.captionEntities ?: emptyList())
            }

            else -> null to emptyList()
        }

        val text = expandTextLinks(rawText, ents)
        log.debug("Expanded text: {}", text?.take(100))

        val photo = msg.photo?.maxByOrNull { it.width * it.height }

        try {
            if (photo != null) {
                log.info(
                    "Posting photo to VK | fileId={} | size={}x{}",
                    photo.fileId, photo.width, photo.height
                )

                val fileInfo = tg.getFile(photo.fileId)
                val path = fileInfo.result.filePath

                if (path == null) {
                    log.warn("TG: file_path is null for fileId={}", photo.fileId)
                    return
                }

                log.debug("Downloading file from: {}", path)
                val bytes = tg.downloadFileBytes(path)
                log.debug("Downloaded {} bytes", bytes.size)

                val fileName = path.substringAfterLast('/')
                log.debug("Uploading to VK with fileName={}", fileName)

                vk.postTextWithPhoto(text, bytes, fileName)

                log.info(
                    "✓ VK: posted photo | chatId={} | messageId={} | size={} bytes",
                    msg.chat.id, msg.messageId, bytes.size
                )

            } else if (!text.isNullOrBlank()) {
                log.info("Posting text to VK | length={}", text.length)
                vk.postText(text)
                log.info(
                    "✓ VK: posted text | chatId={} | messageId={}",
                    msg.chat.id, msg.messageId
                )

            } else {
                log.info("Skip update {} (no text/photo)", upd.updateId)
                return
            }

            // Добавляем в обработанные ТОЛЬКО после успешной отправки
            processedMessages.add(messageKey)

            // ограничиваем кеш последних сообщений (1000)
            if (processedMessages.size > 1000) {
                val toRemove = processedMessages.take(processedMessages.size - 1000)
                processedMessages.removeAll(toRemove.toSet())
                log.debug("Cleaned processed messages cache, size now: {}", processedMessages.size)
            }

        } catch (e: Exception) {
            log.error(
                "Failed to post to VK | chatId={} | messageId={} | error={}",
                msg.chat.id, msg.messageId, e.message, e
            )
            throw e
        }
    }

    private fun expandTextLinks(text: String?, entities: List<TgMessageEntity>?): String? {
        if (text.isNullOrEmpty() || entities.isNullOrEmpty()) return text

        val links = entities.filter { it.type == "text_link" && !it.url.isNullOrBlank() }
            .sortedByDescending { it.offset }

        if (links.isEmpty()) return text

        log.debug("Expanding {} text_link entities", links.size)

        val sb = StringBuilder(text)
        for (e in links) {
            val start = e.offset
            val end = (e.offset + e.length).coerceAtMost(sb.length)
            val visible = sb.substring(start, end)
            val replacement = "$visible ${e.url}"
            sb.replace(start, end, replacement)
            log.trace("Expanded link: '{}' -> '{}'", visible, replacement)
        }
        return sb.toString()
    }
}
