package com.posysaev.socialbridge.service

import com.posysaev.socialbridge.client.telegram.TelegramClient
import com.posysaev.socialbridge.client.vkontakte.VkClient
import com.posysaev.socialbridge.config.TelegramProperties
import com.posysaev.socialbridge.dto.telegram.TgMessageEntity
import com.posysaev.socialbridge.dto.telegram.TgUpdate
import com.posysaev.socialbridge.dto.vk.MixedAlbum
import com.posysaev.socialbridge.dto.vk.MixedMedia
import com.posysaev.socialbridge.dto.vk.VkMedia
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
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
    private val processedMessages = mutableSetOf<String>()

    /** Буфер по media_group_id для альбомов (теперь хранит и фото, и видео) */
    private val albums = ConcurrentHashMap<String, MixedAlbum>()
    private val albumBufferMs = 2500L

    @Scheduled(fixedDelayString = "\${telegram.polling-interval-ms:5000}")
    fun poll() {
        val offset = lastUpdateId.get()
        try {
            val updates = tg.getUpdates(if (offset == 0L) null else offset + 1, tgProps.timeoutSec)
            if (!updates.ok) return

            updates.result.forEach { upd ->
                try {
                    processUpdate(upd)
                } catch (e: Exception) {
                    log.error("Failed to process update ${upd.updateId}", e)
                } finally {
                    lastUpdateId.updateAndGet { cur -> max(cur, upd.updateId) }
                }
            }
            flushExpiredAlbums()
        } catch (e: Exception) {
            log.error("Polling failed", e)
        }
    }

    private fun processUpdate(upd: TgUpdate) {
        val msg = upd.channelPost ?: upd.message ?: return
        if (msg.chat.id !in tgProps.sourceChatIds) return

        val key = "${msg.chat.id}:${msg.messageId}"
        if (!processedMessages.add(key)) return

        val (rawText, ents) = when {
            msg.text != null -> msg.text to (msg.entities ?: emptyList())
            msg.caption != null -> msg.caption to (msg.captionEntities ?: emptyList())
            else -> null to emptyList()
        }
        val text = expandTextLinks(rawText, ents)

        // === часть media_group (может быть фото или видео)
        msg.mediaGroupId?.let { gid ->
            val album = albums.computeIfAbsent(gid) { MixedAlbum(msg.chat.id, System.currentTimeMillis()) }
            album.caption = album.caption ?: text
            if (!msg.captionEntities.isNullOrEmpty()) album.captionEntities = msg.captionEntities
            msg.photo?.maxByOrNull { it.width * it.height }?.let {
                album.items += MixedMedia.Photo(it.fileId)
            }
            msg.video?.let {
                album.items += MixedMedia.Video(it.fileId)
            }
            return
        }

        // === одиночное видео ===
        msg.video?.let { v ->
            val path = tg.getFile(v.fileId).result.filePath ?: return
            val bytes = tg.downloadFileBytes(path)
            val fileName = path.substringAfterLast('/')
            vk.post(text, listOf(VkMedia.Video(bytes, fileName)))
            return
        }

        // === одиночное фото ===
        msg.photo?.maxByOrNull { it.width * it.height }?.let { p ->
            val path = tg.getFile(p.fileId).result.filePath ?: return
            val bytes = tg.downloadFileBytes(path)
            val fileName = path.substringAfterLast('/')
            vk.post(text, listOf(VkMedia.Photo(bytes, fileName)))
            return
        }

        // === просто текст ===
        if (!text.isNullOrBlank()) vk.post(text)
    }

    /** Отправляет «созревшие» альбомы, где могли быть и фото, и видео. */
    private fun flushExpiredAlbums() {
        val now = System.currentTimeMillis()
        val ready = albums.filterValues { now - it.startedAt >= albumBufferMs }.toList()
        ready.forEach { (gid, buf) ->
            try {
                val medias = buf.items.mapNotNull { m ->
                    when (m) {
                        is MixedMedia.Photo -> {
                            val path = tg.getFile(m.fileId).result.filePath ?: return@mapNotNull null
                            val bytes = tg.downloadFileBytes(path)
                            val name = path.substringAfterLast('/')
                            VkMedia.Photo(bytes, name)
                        }

                        is MixedMedia.Video -> {
                            val path = tg.getFile(m.fileId).result.filePath ?: return@mapNotNull null
                            val bytes = tg.downloadFileBytes(path)
                            val name = path.substringAfterLast('/')
                            VkMedia.Video(bytes, name)
                        }
                    }
                }
                if (medias.isNotEmpty()) {
                    val caption = expandTextLinks(buf.caption, buf.captionEntities)
                    vk.post(caption, medias)
                    log.info("VK: posted mixed album {} ({} items)", gid, medias.size)
                }
            } catch (e: Exception) {
                log.error("Failed to post mixed album {}", gid, e)
            } finally {
                albums.remove(gid)
            }
        }
    }

    /** Преобразует Telegram text_link → текст+URL */
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
