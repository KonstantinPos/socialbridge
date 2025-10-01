package com.posysaev.socialbridge.service

import com.posysaev.socialbridge.client.telegram.TelegramClient
import com.posysaev.socialbridge.client.vkontakte.VkClient
import com.posysaev.socialbridge.dto.telegram.TgMessage
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import kotlin.math.max

@EnableScheduling
@Service
class PollingService(
    private val tg: TelegramClient,
    private val vk: VkClient,
    @Value("\${telegram.source-chat-id}") private val sourceChatId: Long,
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

                val msg: TgMessage = upd.channelPost ?: upd.message ?: return@forEach
                if (msg.chat.id != sourceChatId) return@forEach

                val text = msg.text ?: msg.caption
                val photo = msg.photo?.maxByOrNull { it.width * it.height }

                if (photo != null) {
                    val fileInfo = tg.getFile(photo.fileId)
                    val path = fileInfo.result.filePath ?: run {
                        log.warn("TG: file_path is null for {}", photo.fileId); return@forEach
                    }

                    // Никаких ссылок — только нативная загрузка
                    val bytes = tg.downloadFileBytes(path)
                    try {
                        vk.postTextWithPhotoOrThrow(text, bytes, fileName = path.substringAfterLast('/'))
                        log.info("VK: posted native photo (messageId={})", msg.messageId)
                    } catch (e: Exception) {
                        log.error("VK: failed to post native photo: {}", e.message)
                    }
                } else if (!text.isNullOrBlank()) {
                    vk.postText(text)
                    log.info("VK: posted text (messageId={})", msg.messageId)
                } else {
                    log.info("Skip update {} (no text/photo)", upd.updateId)
                }
            }
        } catch (e: Exception) {
            log.error("Polling failed", e)
        }
    }
}

