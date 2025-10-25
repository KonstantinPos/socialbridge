package com.posysaev.socialbridge.client.telegram

import com.posysaev.socialbridge.config.TelegramProperties
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient

@Component
class TelegramPublisher(
    private val props: TelegramProperties,
    @Qualifier("telegramRestClient") private val api: RestClient
) {
    fun sendTextToTarget(text: String) = sendText(props.targetChatId, text)

    fun sendText(chatId: Long, text: String, parseMode: String? = "HTML") {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("chat_id", chatId.toString())
            add("text", text.take(4096))
            parseMode?.let { add("parse_mode", it) }
            add("disable_web_page_preview", "false")
        }
        postForm("/sendMessage", form)
    }

    /** Отправка фото байтами */
    fun sendPhoto(
        bytes: ByteArray,
        filename: String = "photo.jpg",
        caption: String? = null,
        parseMode: String? = "HTML"
    ) {
        val form = LinkedMultiValueMap<String, Any>().apply {
            add("chat_id", props.targetChatId.toString())
            caption?.let { add("caption", it.take(1024)) }
            parseMode?.let { add("parse_mode", it) }
            add("photo", object : ByteArrayResource(bytes) {
                override fun getFilename() = filename
            })
        }
        postMultipart("/sendPhoto", form)
    }

    /** Отправка видео байтами */
    fun sendVideo(
        bytes: ByteArray,
        filename: String = "video.mp4",
        caption: String? = null,
        parseMode: String? = "HTML"
    ) {
        val form = LinkedMultiValueMap<String, Any>().apply {
            add("chat_id", props.targetChatId.toString())
            caption?.let { add("caption", it.take(1024)) }
            parseMode?.let { add("parse_mode", it) }
            add("video", object : ByteArrayResource(bytes) {
                override fun getFilename() = filename
            })
        }
        postMultipart("/sendVideo", form)
    }

    /* ------- низкоуровневые вызовы + защита от 429 ------- */

    private fun postForm(path: String, form: LinkedMultiValueMap<String, String>) {
        try {
            api.post().uri("/bot${props.botToken}$path")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve().toBodilessEntity()
        } catch (e: HttpClientErrorException.TooManyRequests) {
            val retry = Regex("\"retry_after\":(\\d+)").find(e.responseBodyAsString)
                ?.groupValues?.getOrNull(1)?.toLong() ?: 5
            Thread.sleep((retry + 1) * 1000)
            api.post().uri("/bot${props.botToken}$path")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve().toBodilessEntity()
        }
    }

    private fun postMultipart(path: String, form: LinkedMultiValueMap<String, Any>) {
        try {
            api.post().uri("/bot${props.botToken}$path")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve().toBodilessEntity()
        } catch (e: HttpClientErrorException.TooManyRequests) {
            val retry = Regex("\"retry_after\":(\\d+)").find(e.responseBodyAsString)
                ?.groupValues?.getOrNull(1)?.toLong() ?: 5
            Thread.sleep((retry + 1) * 1000)
            api.post().uri("/bot${props.botToken}$path")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve().toBodilessEntity()
        }
    }
}
