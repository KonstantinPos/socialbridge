package com.posysaev.socialbridge.client.telegram

import com.posysaev.socialbridge.config.TelegramProperties
import com.posysaev.socialbridge.dto.telegram.TgFileResponse
import com.posysaev.socialbridge.dto.telegram.TgUpdatesResponse
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
class TelegramClient(
    private val props: TelegramProperties,
    @Qualifier("telegramRestClient")
    private val api: RestClient
) {

    fun getUpdates(offset: Long?, timeoutSec: Int): TgUpdatesResponse {
        return api.get()
            .uri { b ->
                b.path("/bot${props.botToken}/getUpdates")
                    .queryParam("allowed_updates", "message,channel_post")
                    .apply { if (offset != null) queryParam("offset", offset) }
                    .queryParam("timeout", timeoutSec)
                    .build()
            }
            .retrieve()
            .body(TgUpdatesResponse::class.java) ?: TgUpdatesResponse(false, emptyList())
    }

    fun getFile(fileId: String): TgFileResponse =
        api.get()
            .uri { b -> b.path("/bot${props.botToken}/getFile").queryParam("file_id", fileId).build() }
            .retrieve()
            .body(TgFileResponse::class.java)!!

    fun downloadFileBytes(filePath: String): ByteArray {
        val url = "/file/bot${props.botToken}/$filePath"
        val response = api.method(HttpMethod.GET)
            .uri(url)
            .retrieve()
            .toEntity(ByteArray::class.java)
        if (response.statusCode != HttpStatus.OK) {
            throw IllegalStateException("Failed to download file: $filePath")
        }
        return response.body ?: error("Empty file bytes")
    }
}
