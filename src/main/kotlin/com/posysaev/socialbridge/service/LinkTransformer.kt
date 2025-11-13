package com.posysaev.socialbridge.service

import org.springframework.stereotype.Component

@Component
class LinkTransformer(
    private val unshortener: UrlUnshortener,
    private val builder: AffiliateLinkBuilder
) {
    private val urlRegex = Regex("""https?://[^\s)]+""")
    private val tail = "Видел на Али 👀"
    private val tailLineRegex = Regex("""(?m)^\s*Видел на Али 👀\s*$""")

    private fun escapeHtml(s: String) = buildString(s.length) {
        for (ch in s) when (ch) {
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '&' -> append("&amp;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(ch)
        }
    }

    fun transform(text: String?, plain: Boolean = false): String? {
        if (text.isNullOrBlank()) return tail

        var result = text
        var marking: String? = null

        // 1) распаковать и заменить ссылки; параллельно забираем маркировку
        result = urlRegex.replace(result) { mr ->
            val original = unshortener.resolve(mr.value)
            val affiliate = builder.build(original)
            builder.takeMarkingFor(original)?.let { marking = it }
            affiliate
        }

        // 2) почистить мусор
        result = result
            .replace("Мужской AliExpress 💪", "")
            .replace("Мужской AliExpress", "")
            .replace(tailLineRegex, "")
            .trim()
            .replace(Regex("\n{3,}"), "\n\n")

        // 3) добавить хвост
        result = if (result.isBlank()) {
            tail
        } else {
            result.trimEnd() + "\n\n" + tail
        }

        // 4) добавить в конце цитату с маркировкой (без заголовка «Маркировка»)
        marking?.let {
            result += "\n\n" + if (plain) {
                // VK: без HTML
                "Реклама. ${it.trim()}"
            } else {
                // Telegram: HTML разрешён
                "<blockquote>${escapeHtml(it)}</blockquote>"
            }
        }

        return result
    }
}
