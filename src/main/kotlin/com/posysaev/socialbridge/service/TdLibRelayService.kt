package com.posysaev.socialbridge.service

import com.posysaev.socialbridge.client.telegram.TelegramPublisher
import com.posysaev.socialbridge.config.TdlibProperties
import it.tdlight.client.*
import it.tdlight.jni.TdApi
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Service
class TdLibRelayService(
    private val props: TdlibProperties,
    private val publisher: TelegramPublisher,
    private val transformer: LinkTransformer
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private lateinit var client: SimpleTelegramClient

    private val sourceChatIds = ConcurrentHashMap.newKeySet<Long>()
    private val activatedChatIds = ConcurrentHashMap.newKeySet<Long>()
    private val processed = ConcurrentHashMap.newKeySet<String>()

    private data class Pending(
        val type: String,
        val caption: String?,
        val filename: String
    )

    private val pending = ConcurrentHashMap<Int, Pending>()

    @Volatile
    private var lastSendAt = 0L
    private val minDelayMs = 800L

    // Периодический пинг чатов для поддержания активности
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    @PostConstruct
    fun start() {
        if (!props.enabled) return

        val settings = TDLibSettings.create(APIToken(props.apiId, props.apiHash))
        val factory = SimpleTelegramClientFactory()
        client = factory.builder(settings).build(AuthenticationSupplier.user(props.phoneNumber))

        // Ловим дкоачанные файлы и отправляем ботом
        client.addUpdateHandler(TdApi.UpdateFile::class.java) { upd ->
            val f = upd.file
            val meta = pending[f.id] ?: return@addUpdateHandler

            val local = f.local
            if (!local.isDownloadingCompleted || local.path.isNullOrBlank()) return@addUpdateHandler

            try {
                val bytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(local.path))
                val now = System.currentTimeMillis()
                val wait = (lastSendAt + minDelayMs) - now
                if (wait > 0) Thread.sleep(wait)

                when (meta.type) {
                    "photo" -> publisher.sendPhoto(bytes, meta.filename, meta.caption)
                    "video" -> publisher.sendVideo(bytes, meta.filename, meta.caption)
                }
                lastSendAt = System.currentTimeMillis()
                log.debug("Relayed ${meta.type} from path {}", local.path)
            } catch (e: Exception) {
                log.warn("Failed to send ${meta.type}: ${e.message}")
            } finally {
                pending.remove(f.id)
            }
        }

        // Новые сообщения из источников
        client.addUpdateHandler(TdApi.UpdateNewMessage::class.java) { upd ->
            handleMessage(upd.message)
        }

        // КРИТИЧНО: Подписка на обновления чатов
        client.addUpdateHandler(TdApi.UpdateChatLastMessage::class.java) { upd ->
            if (sourceChatIds.contains(upd.chatId)) {
                log.debug("UpdateChatLastMessage for chatId={}", upd.chatId)
                // Можно дополнительно обработать, если нужно
            }
        }

        // Подключаем все источники
        props.sources.forEach { src -> attachSource(src.trim()) }

        // Периодически пингуем чаты каждые 5 минут для поддержания активности
        scheduler.scheduleAtFixedRate({
            sourceChatIds.forEach { chatId ->
                try {
                    refreshChat(chatId)
                } catch (e: Exception) {
                    log.warn("Failed to refresh chat {}: {}", chatId, e.message)
                }
            }
        }, 5, 5, TimeUnit.MINUTES)
    }

    private fun attachSource(src: String) {
        when {
            src.startsWith("@") -> attachPublicByUsername(src)
            isInviteLink(src) -> attachByInvite(src)
            else -> log.warn("TDLib: неизвестный формат источника: {} (ожидаю @username или t.me/+invite)", src)
        }
    }

    private fun attachPublicByUsername(username: String) {
        client.send(TdApi.SearchPublicChat(username)) { res ->
            if (res.isError) {
                val e = res.error
                log.warn("TDLib: source {} не найден: {} {}", username, e.code, e.message)
                return@send
            }
            val chat = res.get()
            sourceChatIds.add(chat.id)
            activateChat(chat.id)
            log.info("TDLib: слушаем публичный источник {} (chatId={})", username, chat.id)
        }
    }

    private fun attachByInvite(link: String) {
        val invite = normalizeInviteLink(link)

        client.send(TdApi.JoinChatByInviteLink(invite)) { res ->
            when {
                res.isError -> {
                    val e = res.error
                    if (e.code == 400 && e.message.contains("USER_ALREADY_PARTICIPANT")) {
                        log.info("TDLib: уже участник чата по инвайту {}, ищем chatId...", invite)
                        getChatIdFromInvite(invite)
                    } else {
                        log.warn(
                            "TDLib: не удалось присоединиться по инвайту {}: {} {}",
                            invite, e.code, e.message
                        )
                    }
                }

                else -> {
                    val chat = res.get()
                    sourceChatIds.add(chat.id)
                    activateChat(chat.id)
                    log.info("TDLib: присоединились к чату по инвайту (chatId={})", chat.id)
                }
            }
        }
    }

    private fun getChatIdFromInvite(invite: String) {
        client.send(TdApi.CheckChatInviteLink(invite)) { res ->
            if (res.isError) {
                log.warn(
                    "TDLib: не удалось получить информацию о чате: {} {}",
                    res.error.code, res.error.message
                )
                return@send
            }

            val info = res.get()
            if (info.chatId != 0L) {
                sourceChatIds.add(info.chatId)
                activateChat(info.chatId)
                log.info("TDLib: добавлен чат из инвайта (chatId={})", info.chatId)
            } else {
                log.warn("TDLib: не удалось получить chatId из инвайта {}", invite)
            }
        }
    }

    private fun activateChat(chatId: Long) {
        if (!activatedChatIds.add(chatId)) {
            return
        }

        // 1) Открываем чат явно
        client.send(TdApi.OpenChat(chatId)) { res ->
            if (res.isError) {
                log.warn("TDLib: OpenChat error chatId {}: {} {}", chatId, res.error.code, res.error.message)
            } else {
                log.debug("TDLib: OpenChat ok chatId {}", chatId)
            }
        }

        // 2) Загружаем последнее сообщение
        client.send(TdApi.GetChatHistory(chatId, 0, 0, 1, false)) { res ->
            if (res.isError) {
                log.warn("TDLib: GetChatHistory error chatId {}: {} {}", chatId, res.error.code, res.error.message)
            } else {
                log.debug("TDLib: GetChatHistory ok chatId {}", chatId)
            }
        }

        // 3) Помечаем как просмотренные (опционально)
        client.send(TdApi.ViewMessages(chatId, longArrayOf(), null, true)) { res ->
            if (res.isError) {
                log.debug("TDLib: ViewMessages warn chatId {}: {} {}", chatId, res.error.code, res.error.message)
            }
        }
    }

    // Периодическое обновление чата для поддержания активности
    private fun refreshChat(chatId: Long) {
        client.send(TdApi.GetChat(chatId)) { res ->
            if (res.isError) {
                log.debug("TDLib: GetChat refresh error chatId {}: {}", chatId, res.error.message)
            } else {
                log.trace("TDLib: Chat {} refreshed", chatId)
            }
        }
    }

    private fun handleMessage(m: TdApi.Message) {
        if (!sourceChatIds.contains(m.chatId)) return

        val key = "${m.chatId}:${m.id}"
        if (!processed.add(key)) return

        when (val c = m.content) {
            is TdApi.MessageText -> {
                transformer.transform(c.text.text)?.let { publisher.sendTextToTarget(it) }
            }

            is TdApi.MessagePhoto -> {
                val photoFile = c.photo.sizes.lastOrNull()?.photo ?: return
                val caption = transformer.transform(c.caption?.text)
                pending[photoFile.id] = Pending(
                    type = "photo",
                    caption = caption,
                    filename = "photo.jpg"
                )
                client.send(TdApi.DownloadFile(photoFile.id, 32, 0, 0, false)) { }
            }

            is TdApi.MessageVideo -> {
                val videoFile = c.video.video
                val caption = transformer.transform(c.caption?.text)
                val name = if (!c.video.fileName.isNullOrBlank()) c.video.fileName else "video.mp4"
                pending[videoFile.id] = Pending(
                    type = "video",
                    caption = caption,
                    filename = name
                )
                client.send(TdApi.DownloadFile(videoFile.id, 32, 0, 0, false)) { }
            }

            else -> {
                // При желании: добавить Document/Animation/Voice и т.п.
            }
        }
    }

    private fun isInviteLink(s: String): Boolean {
        val low = s.lowercase()
        return low.contains("t.me/+") || low.contains("t.me/joinchat/")
    }

    private fun normalizeInviteLink(s: String): String {
        return if (s.startsWith("http://") || s.startsWith("https://")) s else "https://$s"
    }
}