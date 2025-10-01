package com.posysaev.socialbridge.service

import com.posysaev.socialbridge.client.telegram.TelegramClient
import com.posysaev.socialbridge.client.vkontakte.VkClient
import com.posysaev.socialbridge.dto.telegram.TgMessage
import com.posysaev.socialbridge.dto.telegram.TgMessageEntity
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import kotlin.math.max

@Service
@EnableScheduling
class PollingService(
    private val tg: TelegramClient,
    private val vk: VkClient,
    @Value("\${telegram.source-chat-ids}") private val sourceChatIds: Set<Long>,
    @Value("\${telegram.timeout-sec:25}") private val timeoutSec: Int
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private var lastUpdateId: Long? = null

    @Scheduled(fixedDelayString = "\${telegram.polling-interval-ms:5000}")
    fun poll() {
        try {
            val updates = tg.getUpdates(lastUpdateId?.let { it + 1 }, timeoutSec)
            if (!updates.ok) return

            updates.result.forEach { upd ->
                lastUpdateId = max(lastUpdateId ?: 0, upd.updateId)

                val msg = upd.channelPost ?: upd.message ?: return@forEach
                if (msg.chat.id !in sourceChatIds) return@forEach

                val (rawText, ents) = when {
                    msg.text != null    -> msg.text to (msg.entities ?: emptyList())
                    msg.caption != null -> msg.caption to (msg.captionEntities ?: emptyList())
                    else -> null to emptyList()
                }
                val text = expandTextLinks(rawText, ents)

                val photo = msg.photo?.maxByOrNull { it.width * it.height }
                if (photo != null) {
                    val fileInfo = tg.getFile(photo.fileId)
                    val path = fileInfo.result.filePath ?: run {
                        log.warn("TG: file_path is null for {}", photo.fileId); return@forEach
                    }
                    val bytes = tg.downloadFileBytes(path)
                    vk.postTextWithPhotoOrThrow(text, bytes, fileName = path.substringAfterLast('/'))
                    log.info("VK: posted native photo (chatId={}, messageId={})", msg.chat.id, msg.messageId)
                } else if (!text.isNullOrBlank()) {
                    vk.postText(text)
                    log.info("VK: posted text (chatId={}, messageId={})", msg.chat.id, msg.messageId)
                } else {
                    log.info("Skip update {} (no text/photo)", upd.updateId)
                }
            }
        } catch (e: Exception) {
            log.error("Polling failed", e)
            throw e
        }
    }

    private fun expandTextLinks(text: String?, entities: List<TgMessageEntity>?): String? {
        if (text.isNullOrEmpty() || entities.isNullOrEmpty()) return text
        val links = entities.filter { it.type == "text_link" && !it.url.isNullOrBlank() }
            .sortedByDescending { it.offset }

        if (links.isEmpty()) return text
        val sb = StringBuilder(text)
        for (e in links) {
            val start = e.offset
            val end = (e.offset + e.length).coerceAtMost(sb.length)
            val visible = sb.substring(start, end)
            val replacement = "$visible ${e.url}"
            sb.replace(start, end, replacement)
        }
        return sb.toString()
    }
}
