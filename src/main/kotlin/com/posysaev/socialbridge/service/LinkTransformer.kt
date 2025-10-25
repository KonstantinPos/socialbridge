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

    fun transform(text: String?): String? {
        // Если совсем пусто — всё равно вернём хвост:
        if (text.isNullOrBlank()) return tail

        var result = text

        // 1) Обработка ссылок (распаковать + обернуть партнёркой)
        result = urlRegex.replace(result) { mr ->
            val final = unshortener.resolve(mr.value)
            builder.build(final)
        }

        // 2) Удаляем исходные подписи-брендинг источника
        // (дальше сами добавим нужный хвост в конец)
        result = result
            .replace("Мужской AliExpress 💪", "")
            .replace("Мужской AliExpress", "")

        // 3) Удаляем все существующие экземпляры хвоста в любом месте,
        // чтобы не было дублей
        result = result.replace(tailLineRegex, "")

        // 4) Чистим лишние пробелы/пустые строки
        result = result.trim()
        result = result.replace(Regex("\\n{3,}"), "\n\n")

        // 5) Гарантируем хвост в конце, с пустой строкой перед ним
        result = if (result.isBlank()) {
            tail
        } else {
            // Если вдруг хвост оказался на той же строке — отделяем
            val needsGap = !result.endsWith("\n\n")
            buildString(result.length + tail.length + 2) {
                append(result!!.trimEnd())
                if (needsGap) append("\n\n")
                append(tail)
            }
        }

        return result
    }
}
