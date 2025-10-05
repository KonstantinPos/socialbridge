package com.posysaev.socialbridge.client.vkontakte

import com.fasterxml.jackson.databind.ObjectMapper
import com.posysaev.socialbridge.config.VkProperties
import com.posysaev.socialbridge.dto.vk.*
import com.posysaev.socialbridge.service.RetryService
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.slf4j.LoggerFactory
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient

/**
 * Универсальный клиент для публикации во ВКонтакте:
 * - текст, фото (1+), видео (1+), микс фото+видео
 */
@Component
class VkClient(
    private val props: VkProperties,
    private val retry: RetryService,
    private val objectMapper: ObjectMapper,
    private val httpClient: OkHttpClient
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val api = RestClient.builder().baseUrl("https://api.vk.com/method").build()

    /** Универсальная публикация: один wall.post */
    fun post(message: String?, media: List<VkMedia> = emptyList()) {
        if (media.isEmpty()) {
            postToWall(message, emptyList()); return
        }

        val attachments = buildList {
            // фото
            media.filterIsInstance<VkMedia.Photo>().forEach { add(uploadPhoto(it.bytes, it.fileName)) }
            // видео
            media.filterIsInstance<VkMedia.Video>().forEach { add(uploadVideo(it.bytes, it.fileName, message)) }
        }

        postToWall(message, attachments)
    }

    private fun postToWall(message: String?, attachments: List<String>) {
        val resp = retry.retry {
            api.get().uri { b ->
                b.path("/wall.post")
                    .queryParam("owner_id", -props.groupId)
                    .queryParam("from_group", 1)
                    .apply { if (!message.isNullOrBlank()) queryParam("message", message.take(4096)) }
                    .apply { if (attachments.isNotEmpty()) queryParam("attachments", attachments.joinToString(",")) }
                    .queryParam("access_token", props.userAccessToken)
                    .queryParam("v", props.apiVersion)
                    .build()
            }.retrieve().body(VkResponse::class.java)
        }
        resp?.error?.let { throw IllegalStateException("VK error ${it.errorCode}: ${it.errorMsg}") }
        log.info("VK: post published (attachments: ${attachments.size})")
    }

    /** Фото → photo{owner_id}_{id} */
    private fun uploadPhoto(bytes: ByteArray, fileName: String): String {
        val uploadUrl = retry.retry {
            val res = api.get().uri { b ->
                b.path("/photos.getWallUploadServer")
                    .queryParam("group_id", props.groupId)
                    .queryParam("access_token", props.userAccessToken)
                    .queryParam("v", props.apiVersion)
                    .build()
            }.retrieve().body(VkUploadServerResponse::class.java)
            res?.error?.let { throw IllegalStateException("VK error ${it.errorCode}: ${it.errorMsg}") }
            (res?.response?.get("upload_url") as? String) ?: error("No upload_url returned for photo")
        }

        val resource = object : ByteArrayResource(bytes) {
            override fun getFilename() = fileName
            override fun contentLength() = bytes.size.toLong()
        }
        val multipart = LinkedMultiValueMap<String, Any>().apply { add("photo", resource) }

        val uploadClient = RestClient.builder().baseUrl(uploadUrl).build()
        val uploadResult = uploadClient.post()
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(multipart)
            .retrieve()
            .body(VkUploadResult::class.java)
            ?: error("Empty upload result (photo)")

        val saved = retry.retry {
            val form = LinkedMultiValueMap<String, String>().apply {
                add("group_id", props.groupId.toString())
                add("photo", uploadResult.photo)
                add("server", uploadResult.server.toString())
                add("hash", uploadResult.hash)
                add("access_token", props.userAccessToken)
                add("v", props.apiVersion)
            }
            api.post().uri("/photos.saveWallPhoto")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(VkSaveWallPhotoResponse::class.java)
        }

        saved?.error?.let { throw IllegalStateException("VK error ${it.errorCode}: ${it.errorMsg}") }
        val ph = saved?.response?.firstOrNull() ?: error("No photo in saveWallPhoto response")
        val attach = "photo${ph.ownerId}_${ph.id}"
        log.info("VK: uploaded photo {}", attach)
        return attach
    }

    /** Видео → video{owner_id}_{video_id} (upload через OkHttp, чтобы не ловить 406) */
    private fun uploadVideo(bytes: ByteArray, fileName: String, message: String?): String {
        val saveResp = retry.retry {
            val form = LinkedMultiValueMap<String, String>().apply {
                add("group_id", props.groupId.toString())
                val title = message?.lineSequence()?.firstOrNull()?.take(80)
                    ?: fileName.substringBeforeLast('.', fileName)
                add("name", title)
                if (!message.isNullOrBlank()) add("description", message.take(4000))
                add("wallpost", "0")
                add("access_token", props.userAccessToken)
                add("v", props.apiVersion)
            }
            api.post().uri("/video.save")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(VkVideoSaveResponse::class.java)
                ?: error("Empty video.save response")
        }
        saveResp.error?.let { throw IllegalStateException("VK error ${it.errorCode}: ${it.errorMsg}") }
        val uploadUrl = saveResp.response?.uploadUrl ?: error("No upload_url from video.save")

        val mediaType = when {
            fileName.endsWith(".mp4", true) -> "video/mp4"
            fileName.endsWith(".mov", true) -> "video/quicktime"
            fileName.endsWith(".mkv", true) -> "video/x-matroska"
            else -> "application/octet-stream"
        }.toMediaTypeOrNull()

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("video_file", fileName, RequestBody.create(mediaType, bytes))
            .build()

        val request = Request.Builder()
            .url(uploadUrl)
            .post(body)
            .header("Accept", "*/*")
            .header("Connection", "keep-alive")
            .header("User-Agent", "okhttp/4.12")
            .build()

        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("VK upload failed: HTTP ${resp.code} ${resp.message}")
            val jsonStr = resp.body?.string() ?: error("Empty video upload result")
            val upload = objectMapper.readValue(jsonStr, VkVideoUploadResult::class.java)
            val attach = "video${upload.ownerId}_${upload.videoId}"
            log.info("VK: uploaded video {}", attach)
            return attach
        }
    }
}
