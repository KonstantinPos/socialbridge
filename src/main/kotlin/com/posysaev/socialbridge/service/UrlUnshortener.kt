package com.posysaev.socialbridge.service

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.springframework.stereotype.Component
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI

@Component
class UrlUnshortener {
    private val cm = CookieManager().apply { setCookiePolicy(CookiePolicy.ACCEPT_ALL) }
    private val jar = object : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val cookies = cm.cookieStore.get(URI(url.scheme, url.host, null, null))
            return cookies?.mapNotNull { Cookie.parse(url, "${it.name}=${it.value}") } ?: emptyList()
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach {
                val c = java.net.HttpCookie(it.name, it.value).apply {
                    domain = it.domain; path = it.path; secure = it.secure
                }
                cm.cookieStore.add(URI(url.scheme, url.host, null, null), c)
            }
        }
    }
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(jar)
        .build()

    fun resolve(startUrl: String, maxHops: Int = 12): String {
        var url = startUrl
        repeat(maxHops) {
            val resp = client.newCall(
                okhttp3.Request.Builder()
                    .url(url)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36"
                    )
                    .get().build()
            ).execute()
            resp.use { r ->
                val code = r.code
                if (code in 300..399) {
                    val loc = r.header("Location") ?: return url
                    url = URI(url).resolve(loc).toString(); return@repeat
                }
                if (code == 200) {
                    val body = r.body?.string().orEmpty()
                    // meta refresh
                    Jsoup.parse(body).selectFirst("meta[http-equiv~=(?i)refresh]")?.let { m ->
                        val content = m.attr("content")
                        val target = content.substringAfter("url=", "").trim('\'', '"', ' ')
                        if (target.isNotBlank()) {
                            url = URI(url).resolve(target).toString(); return@repeat
                        }
                    }
                    return url
                }
                return url
            }
        }
        return url
    }
}

@Component
// простая заглушка: пока возвращаем как есть (сюда вставите вашу партнёрку)
class AffiliateLinkBuilder {
    fun build(resolvedProductUrl: String): String = resolvedProductUrl
}
