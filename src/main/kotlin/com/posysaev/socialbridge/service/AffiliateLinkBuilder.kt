package com.posysaev.socialbridge.service

import it.tdlight.client.SimpleTelegramClient
import it.tdlight.jni.TdApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

@Component
class AffiliateLinkBuilder(
    private val client: SimpleTelegramClient
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val linkCache = ConcurrentHashMap<String, String>()

    private val pendingRequests = ConcurrentHashMap<String, String>()
    private val PENDING = "__PENDING__"

    private val pendingMarkings = ConcurrentHashMap<String, String>()

    @Volatile private var epnBotChatId: Long? = null
    fun setEpnBotChatId(id: Long) { epnBotChatId = id }
    fun getEpnBotChatId(): Long? = epnBotChatId

    private val EPN_BOT_USERNAME = "epnWebmasterbot"

    fun build(resolvedProductUrl: String): String {
        if (!isAliExpressUrl(resolvedProductUrl)) return resolvedProductUrl

        // кэш
        linkCache[resolvedProductUrl]?.let { return it }

        val chatId = epnBotChatId
        if (chatId == null) {
            log.warn("chatId @{} ещё не инициализирован — возвращаю оригинальную ссылку", EPN_BOT_USERNAME)
            return resolvedProductUrl
        }

        sendLinkToBot(chatId, resolvedProductUrl)

        val affiliateLink = waitForBotResponse(resolvedProductUrl, timeoutMs = 20_000)

        return if (!affiliateLink.isNullOrBlank()) {
            linkCache[resolvedProductUrl] = affiliateLink
            affiliateLink
        } else {
            log.warn("EPN бот не ответил, используем оригинальную ссылку")
            resolvedProductUrl
        }
    }

    /** Забрать (и удалить) маркировку для конкретной исходной ссылки */
    fun takeMarkingFor(originalUrl: String): String? = pendingMarkings.remove(originalUrl)

    /** Обработка входящего сообщения от @epnWebmasterbot */
    fun processBotMessage(message: TdApi.Message) {
        try {
            val text = when (val c = message.content) {
                is TdApi.MessageText -> c.text.text
                else -> return
            }
            if (text.isBlank()) return

            val affiliateLink = extractAffiliateLink(text) ?: return
            val itemId = extractItemId(text)
            if (itemId.isBlank()) return

            // ищем соответствующую «заявку» по itemId
            val originalUrl = pendingRequests.entries
                .firstOrNull { (orig, resp) -> resp == PENDING && extractItemId(orig) == itemId }
                ?.key
                ?: return

            pendingRequests[originalUrl] = affiliateLink
            extractMarking(text)?.let { pendingMarkings[originalUrl] = it }
        } catch (e: Exception) {
            log.error("Ошибка обработки сообщения от бота: {}", e.message)
        }
    }

    // --- TDLib helpers ---

    private fun sendLinkToBot(chatId: Long, url: String) {
        val inputMessage = TdApi.InputMessageText(
            TdApi.FormattedText(url, emptyArray()),
            null, true
        )
        pendingRequests[url] = PENDING
        client.send(TdApi.SendMessage(chatId, 0, null, null, null, inputMessage)) { res ->
            if (res == null) {
                log.error("SendMessage callback returned null (TDLib offline?)")
                pendingRequests.remove(url)
                return@send
            }
            if (res.isError) {
                log.error("Ошибка отправки ссылки боту: {} {}", res.error.code, res.error.message)
                pendingRequests.remove(url)
            }
        }
    }

    private fun waitForBotResponse(originalUrl: String, timeoutMs: Long): String? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val response = pendingRequests[originalUrl]
            if (response != null && response != PENDING) {
                pendingRequests.remove(originalUrl)
                return response
            }
            Thread.sleep(300)
        }
        pendingRequests.remove(originalUrl)
        return null
    }

    // --- Parsing helpers ---

    private fun extractAffiliateLink(text: String): String? {
        // сначала ищем явную alii.pub
        Regex("""https?://alii\.pub/\S+""").find(text)?.value?.let { return it }

        // запасной путь — блок "сокращенная:"
        val lines = text.lines()
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.startsWith("сокращенная:", true) || line.startsWith("сокращённая:", true)) {
                if (i + 1 < lines.size) {
                    val link = lines[i + 1].trim()
                    if (link.startsWith("http")) return link
                }
            }
        }
        return null
    }

    /** Берём последнюю строку, начинающуюся с «Реклама.» */
    private fun extractMarking(text: String): String? =
        Regex("(?mi)^\\s*Реклама\\..*$").findAll(text).lastOrNull()?.value?.trim()

    private fun extractItemId(textOrUrl: String): String {
        val patterns = listOf(
            Regex("""item/(\d+)\.html"""),
            Regex("""[?&]sku_id=(\d+)"""),
            Regex("""[?&]item_id=(\d+)"""),
            Regex("""[?&](?:_id|feed_id)=(\d+)""")
        )
        for (p in patterns) {
            p.find(textOrUrl)?.groupValues?.get(1)?.let { return it.filter { ch -> ch.isDigit() } }
        }
        return ""
    }

    private fun isAliExpressUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("aliexpress.") ||
                lower.contains("alii.pub") ||
                lower.contains("ali.ski") ||
                lower.contains("a.aliexpress.")
    }
}
