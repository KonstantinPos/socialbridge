package com.posysaev.socialbridge.service

import com.posysaev.socialbridge.client.telegram.TelegramPublisher
import com.posysaev.socialbridge.client.vkontakte.VkClient
import com.posysaev.socialbridge.config.TdlibProperties
import com.posysaev.socialbridge.config.VkProperties
import com.posysaev.socialbridge.dto.vk.VkMedia
import it.tdlight.client.SimpleTelegramClient
import it.tdlight.jni.TdApi
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Service
class TdLibRelayService(
    private val props: TdlibProperties,
    private val publisher: TelegramPublisher,
    private val client: SimpleTelegramClient,
    private val transformer: LinkTransformer,
    @Lazy private val affiliateLinkBuilder: AffiliateLinkBuilder,
    private val vk: VkClient,
    private val vkProps: VkProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val sourceChatIds = ConcurrentHashMap.newKeySet<Long>()
    private val activatedChatIds = ConcurrentHashMap.newKeySet<Long>()
    private val processed = ConcurrentHashMap.newKeySet<String>()

    // Строка, которую ВСЕГДА добавляем в конец текста для VK
    private val VK_MARKING = "Реклама. ООО \"АЛИБАБА.КОМ (РУ)\" ИНН 7703380158"

    private fun ensureVkMarking(text: String?): String {
        val base = text?.trim() ?: ""
        // если уже есть слово "Реклама" — не дублируем
        return if (base.contains("Реклама")) base
        else listOf(base, VK_MARKING).filter { it.isNotBlank() }.joinToString("\n\n")
    }

    private data class Pending(
        val type: String,           // "photo" | "video"
        val captionTg: String?,
        val captionVk: String?,
        val filename: String
    )

    private val pending = ConcurrentHashMap<Int, Pending>()

    @Volatile private var lastSendAt = 0L
    private val minDelayMs = 800L

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val worker = Executors.newFixedThreadPool(2)

    private val EPN_BOT_USERNAME = "epnWebmasterbot"

    private val targetTgChatId: Long get() = props.targetChatId
    private fun vkGroupForTarget(): Long = vkProps.routeMap[targetTgChatId] ?: vkProps.groupId

    @PostConstruct
    fun start() {
        if (!props.enabled) return

        // Когда TDLib докачал файл — публикуем в TG и зеркалим в VK
        client.addUpdateHandler(TdApi.UpdateFile::class.java) { upd ->
            val f = upd.file
            val meta = pending[f.id] ?: return@addUpdateHandler

            val local = f.local
            if (!local.isDownloadingCompleted || local.path.isNullOrBlank()) return@addUpdateHandler

            try {
                val bytes = Files.readAllBytes(Path.of(local.path))
                val now = System.currentTimeMillis()
                val wait = (lastSendAt + minDelayMs) - now
                if (wait > 0) Thread.sleep(wait)

                when (meta.type) {
                    "photo" -> {
                        // TG — с HTML
                        publisher.sendPhoto(bytes, meta.filename, meta.captionTg)
                        // VK — без HTML + гарантированная маркировка
                        val vkText = ensureVkMarking(meta.captionVk)
                        vk.post(vkText, listOf(VkMedia.Photo(bytes, meta.filename)), vkGroupForTarget())
                    }
                    "video" -> {
                        publisher.sendVideo(bytes, meta.filename, meta.captionTg)
                        val vkText = ensureVkMarking(meta.captionVk)
                        vk.post(vkText, listOf(VkMedia.Video(bytes, meta.filename)), vkGroupForTarget())
                    }
                }
                lastSendAt = System.currentTimeMillis()
            } catch (e: Exception) {
                log.warn("Failed to relay ${meta.type}: ${e.message}")
            } finally {
                pending.remove(f.id)
            }
        }

        client.addUpdateHandler(TdApi.UpdateNewMessage::class.java) { upd ->
            worker.execute { handleMessage(upd.message) }
        }

        // источники из настроек
        props.sources.forEach { src -> attachSource(src.trim()) }

        // найти чат EPN-бота
        client.send(TdApi.SearchPublicChat(EPN_BOT_USERNAME)) { res ->
            if (!res.isError) affiliateLinkBuilder.setEpnBotChatId(res.get().id)
        }

        // пингуем чаты
        scheduler.scheduleAtFixedRate({
            sourceChatIds.forEach { chatId ->
                try { refreshChat(chatId) } catch (_: Exception) {}
            }
        }, 5, 5, TimeUnit.MINUTES)
    }

    private fun attachSource(src: String) {
        when {
            src.startsWith("@") -> attachPublicByUsername(src)
            isInviteLink(src) -> attachByInvite(src)
            else -> log.warn("TDLib: неизвестный формат источника: {}", src)
        }
    }

    private fun attachPublicByUsername(username: String) {
        client.send(TdApi.SearchPublicChat(username)) { res ->
            if (res.isError) return@send
            val chat = res.get()
            sourceChatIds.add(chat.id)
            activateChat(chat.id)
        }
    }

    private fun attachByInvite(link: String) {
        val invite = normalizeInviteLink(link)
        client.send(TdApi.JoinChatByInviteLink(invite)) { res ->
            if (res.isError) {
                if (res.error.code == 400 && res.error.message.contains("USER_ALREADY_PARTICIPANT")) {
                    getChatIdFromInvite(invite)
                }
            } else {
                val chat = res.get()
                sourceChatIds.add(chat.id)
                activateChat(chat.id)
            }
        }
    }

    private fun getChatIdFromInvite(invite: String) {
        client.send(TdApi.CheckChatInviteLink(invite)) { res ->
            if (res.isError) return@send
            val info = res.get()
            if (info.chatId != 0L) {
                sourceChatIds.add(info.chatId)
                activateChat(info.chatId)
            }
        }
    }

    private fun activateChat(chatId: Long) {
        if (!activatedChatIds.add(chatId)) return
        client.send(TdApi.OpenChat(chatId)) { }
        client.send(TdApi.GetChatHistory(chatId, 0, 0, 1, false)) { }
    }

    private fun refreshChat(chatId: Long) {
        client.send(TdApi.GetChat(chatId)) { }
    }

    private fun handleMessage(m: TdApi.Message) {
        val epnId = affiliateLinkBuilder.getEpnBotChatId()
        if (epnId != null && m.chatId == epnId) {
            affiliateLinkBuilder.processBotMessage(m)
            return
        }

        if (!sourceChatIds.contains(m.chatId)) return

        val key = "${m.chatId}:${m.id}"
        if (!processed.add(key)) return

        when (val c = m.content) {
            is TdApi.MessageText -> {
                val textForTg = transformer.transform(c.text.text, plain = false)
                val textForVk = transformer.transform(c.text.text, plain = true)

                if (!textForTg.isNullOrBlank()) {
                    publisher.sendTextToTarget(textForTg)
                }
                if (!textForVk.isNullOrBlank()) {
                    val vkText = ensureVkMarking(textForVk)
                    vk.post(vkText, groupId = vkGroupForTarget())
                }
            }

            is TdApi.MessagePhoto -> {
                val photoFile = c.photo.sizes.lastOrNull()?.photo ?: return
                val captionTg = transformer.transform(c.caption?.text, plain = false)
                val captionVk = transformer.transform(c.caption?.text, plain = true)

                pending[photoFile.id] = Pending(
                    type = "photo",
                    captionTg = captionTg,
                    captionVk = captionVk,
                    filename = "photo.jpg"
                )
                client.send(TdApi.DownloadFile(photoFile.id, 32, 0, 0, false)) { }
            }

            is TdApi.MessageVideo -> {
                val videoFile = c.video.video
                val captionTg = transformer.transform(c.caption?.text, plain = false)
                val captionVk = transformer.transform(c.caption?.text, plain = true)
                val name = if (!c.video.fileName.isNullOrBlank()) c.video.fileName else "video.mp4"

                pending[videoFile.id] = Pending(
                    type = "video",
                    captionTg = captionTg,
                    captionVk = captionVk,
                    filename = name
                )
                client.send(TdApi.DownloadFile(videoFile.id, 32, 0, 0, false)) { }
            }

            else -> { /* игнор остальных типов */ }
        }
    }

    private fun isInviteLink(s: String): Boolean {
        val low = s.lowercase()
        return low.contains("t.me/+") || low.contains("t.me/joinchat/")
    }

    private fun normalizeInviteLink(s: String): String =
        if (s.startsWith("http://") || s.startsWith("https://")) s else "https://$s"
}
