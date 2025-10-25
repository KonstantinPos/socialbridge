package com.posysaev.socialbridge.service

import org.springframework.stereotype.Component

@Component
class LinkTransformer(
    private val unshortener: UrlUnshortener,
    private val builder: AffiliateLinkBuilder
) {
    private val urlRegex = Regex("""https?://[^\s)]+""")

    fun transform(text: String?): String? {
        if (text.isNullOrBlank()) return text

        var result = text

        // --- 1️⃣ Обработка ссылок ---
        result = urlRegex.replace(result) { mr ->
            val final = unshortener.resolve(mr.value)
            builder.build(final)
        }

        // --- 2️⃣ Замена исходного названия группы ---
        // Сначала с эмодзи, потом без, чтобы не оставить "хвосты"
        result = result
            .replace("Мужской AliExpress 💪", "Видел на Али 👀")
            .replace("Мужской AliExpress", "Видел на Али 👀")

        // --- 3️⃣ Убираем лишние пробелы/пустые строки ---
        result = result.trim().replace(Regex("\\n{3,}"), "\n\n")

        return result
    }
}
