package com.posysaev.socialbridge.client.telegram

import com.posysaev.socialbridge.dto.telegram.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
class TelegramClient(
    @Value("\${telegram.bot-token}") private val token: String
) {
    private val api = RestClient.builder()
        .baseUrl("https://api.telegram.org/bot$token")
        .build()

    private val fileApi = RestClient.builder()
        .baseUrl("https://api.telegram.org")
        .build()

    fun getUpdates(offset: Long?, timeoutSec: Int): TgUpdatesResponse {
        val response = api.get()
            .uri { b ->
                b.path("/getUpdates")
                    .queryParam("allowed_updates", "message,channel_post")
                    .apply { if (offset != null) queryParam("offset", offset) }
                    .queryParam("timeout", timeoutSec)
                    .build()
            }
            .retrieve()
            .body(TgUpdatesResponse::class.java) ?: TgUpdatesResponse(false, emptyList())

        return response
    }


    fun getFile(fileId: String): TgFileResponse =
        api.get()
            .uri { b -> b.path("/getFile").queryParam("file_id", fileId).build() }
            .retrieve()
            .body(TgFileResponse::class.java)!!

    fun downloadFileBytes(filePath: String): ByteArray {
        val url = "/file/bot$token/$filePath"
        val response = fileApi.method(HttpMethod.GET)
            .uri(url)
            .retrieve()
            .toEntity(ByteArray::class.java)
        if (response.statusCode != HttpStatus.OK) {
            throw IllegalStateException("Failed to download file: $filePath")
        }
        return response.body ?: error("Empty file bytes")
    }
}
