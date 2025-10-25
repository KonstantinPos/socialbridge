package com.posysaev.socialbridge.service

import com.posysaev.socialbridge.client.telegram.TelegramPublisher
import com.posysaev.socialbridge.config.TdlibProperties
import it.tdlight.client.*
import it.tdlight.jni.TdApi
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class TdLibRelayService(
    private val props: TdlibProperties,
    private val publisher: TelegramPublisher,
    private val transformer: LinkTransformer
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private lateinit var client: SimpleTelegramClient
    private val sourceChatIds = ConcurrentHashMap.newKeySet<Long>()

    // дедупликация апдейтов
    private val processed = ConcurrentHashMap.newKeySet<String>()

    // что мы ждём докачать: fileId -> метаданные
    private data class Pending(
        val type: String,             // "photo" | "video"
        val caption: String?,
        val filename: String
    )

    private val pending = ConcurrentHashMap<Int, Pending>()

    // лёгкий рейт-лимит на отправку в Bot API
    @Volatile
    private var lastSendAt = 0L
    private val minDelayMs = 800L

    @PostConstruct
    fun start() {
        if (!props.enabled) return
        val settings = TDLibSettings.create(APIToken(props.apiId, props.apiHash))
        val factory = SimpleTelegramClientFactory()
        client = factory.builder(settings).build(AuthenticationSupplier.user(props.phoneNumber))

        client.addUpdateHandler(TdApi.UpdateFile::class.java) { upd ->
            val f = upd.file
            val meta = pending[f.id] ?: return@addUpdateHandler

            val local = f.local
            if (!local.isDownloadingCompleted || local.path.isNullOrBlank()) return@addUpdateHandler

            // готово: читаем файл и шлём ботом
            try {
                val bytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(local.path))
                // маленькая задержка, чтобы не ловить 429
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
                pending.remove(f.id) // обязательно очищаем
            }
        }


        client.addUpdateHandler(TdApi.UpdateNewMessage::class.java) { upd ->
            handleMessage(upd.message)
        }

        props.sources.forEach { src -> attachSource(src.trim()) }
    }

    /** Подключение к источнику: публичный @username или инвайт-ссылка */
    private fun attachSource(src: String) {
        when {
            src.startsWith("@") -> attachPublicByUsername(src)
            isInviteLink(src) -> attachByInvite(src)
            else -> log.warn("TDLib: неизвестный формат источника: {} (ожидаю @username или t.me/+invite)", src)
        }
    }

    /** Публичный канал/группа по @username */
    private fun attachPublicByUsername(username: String) {
        client.send(TdApi.SearchPublicChat(username)) { res ->
            if (res.isError) {
                val e = res.error
                log.warn("TDLib: source {} не найден: {} {}", username, e.code, e.message)
                return@send
            }
            val chat = res.get()
            // запоминаем id источника
            sourceChatIds.add(chat.id)
            client.send(TdApi.ViewMessages(chat.id, longArrayOf(), null, true))
            log.info("TDLib: слушаем публичный источник {} (chatId={})", username, chat.id)
        }
    }

    /** Присоединение по инвайт-ссылке t.me/+xxxx или t.me/joinchat/xxxx */
    private fun attachByInvite(link: String) {
        val invite = normalizeInviteLink(link)

        client.send(TdApi.JoinChatByInviteLink(invite)) { res ->
            when {
                res.isError -> {
                    val e = res.error
                    // Если уже участник - это нормально, просто логируем
                    if (e.code == 400 && e.message.contains("USER_ALREADY_PARTICIPANT")) {
                        log.info("TDLib: уже участник чата по инвайту {}, ищем chatId...", invite)
                        // Пытаемся получить информацию о чате
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
                    log.info("TDLib: присоединились к чату по инвайту (chatId={})", chat.id)
                    client.send(TdApi.ViewMessages(chat.id, longArrayOf(), null, true))
                }
            }
        }
    }

    /** Получение chatId из invite-ссылки через CheckChatInviteLink */
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
                log.info("TDLib: добавлен чат из инвайта (chatId={})", info.chatId)
                client.send(TdApi.ViewMessages(info.chatId, longArrayOf(), null, true))
            } else {
                log.warn("TDLib: не удалось получить chatId из инвайта {}", invite)
            }
        }
    }

    /** Обработка сообщения: берём текст/подпись, заменяем ссылки, отправляем в вашу TG-группу */
    /** Обработка сообщения: берём текст/подпись/медиа и шлём в целевой канал */
    private fun handleMessage(m: TdApi.Message) {
        if (!sourceChatIds.contains(m.chatId)) return
        // if (m.isOutgoing) return // при желании

        val key = "${m.chatId}:${m.id}"
        if (!processed.add(key)) return

        when (val c = m.content) {
            is TdApi.MessageText -> {
                transformer.transform(c.text.text)?.let { publisher.sendTextToTarget(it) }
            }

            is TdApi.MessagePhoto -> {
                val photoFile = c.photo.sizes.lastOrNull()?.photo ?: return
                val caption = transformer.transform(c.caption?.text) // ⟵ добавили
                // регистрируем ожидание и просим TDLib докачать
                pending[photoFile.id] = Pending(
                    type = "photo",
                    caption = caption,
                    filename = "photo.jpg"
                )
                client.send(TdApi.DownloadFile(photoFile.id, 32, 0, 0, false)) { }
            }

            is TdApi.MessageVideo -> {
                val videoFile = c.video.video
                val caption = transformer.transform(c.caption?.text) // ⟵ добавили
                val name = if (!c.video.fileName.isNullOrBlank()) c.video.fileName else "video.mp4"
                pending[videoFile.id] = Pending(
                    type = "video",
                    caption = caption,
                    filename = name
                )
                client.send(TdApi.DownloadFile(videoFile.id, 32, 0, 0, false)) { }
            }

            else -> {
                // при желании: добавить Document/Animation/Voice и т.п.
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
