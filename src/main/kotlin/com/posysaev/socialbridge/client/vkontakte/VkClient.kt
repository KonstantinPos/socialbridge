package com.posysaev.socialbridge.client.vkontakte

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.client.RestClient

@Component
class VkClient(
    @Value("\${vk.access-token}") private val groupToken: String,
    @Value("\${vk.user-access-token:}") private val userToken: String,
    @Value("\${vk.group-id}") private val groupId: Long,
    @Value("\${vk.api-version}") private val apiVersion: String
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val api = RestClient.builder().baseUrl("https://api.vk.com/method").build()

    fun postText(message: String) {
        log.debug("VK: Posting text message, length={}", message.length)
        val resp = api.get().uri { b ->
            b.path("/wall.post")
                .queryParam("owner_id", -groupId)
                .queryParam("from_group", 1)
                .queryParam("message", message.take(4096))
                .queryParam("access_token", groupToken)
                .queryParam("v", apiVersion)
                .build()
        }.retrieve().body(VkResponse::class.java)

        resp?.error?.let {
            log.error("VK wall.post error: code={}, msg={}", it.error_code, it.error_msg)
            throw IllegalStateException("VK error ${it.error_code}: ${it.error_msg}")
        }
        log.info("VK: Text posted successfully")
    }

    fun postTextWithPhotoOrThrow(message: String?, imageBytes: ByteArray, fileName: String = "photo.jpg") {
        log.info("VK: Starting photo upload | size={} bytes | fileName={}", imageBytes.size, fileName)

        require(userToken.isNotBlank()) {
            "vk.user-access-token is empty; photos.* require a valid USER token"
        }

        // Retry логика для временных сбоев VK (до 3 попыток)
        var lastException: Exception? = null
        repeat(3) { attempt ->
            try {
                if (attempt > 0) {
                    val delayMs = 1000L * attempt // 1s, 2s
                    log.warn("VK: Retry attempt {} after {}ms delay", attempt + 1, delayMs)
                    Thread.sleep(delayMs)
                }
                postTextWithPhotoInternal(message, imageBytes, fileName)
                log.info("VK: Photo upload succeeded on attempt {}", attempt + 1)
                return // Успех - выходим
            } catch (e: Exception) {
                lastException = e
                log.warn("VK: Upload attempt {} failed: {}", attempt + 1, e.message)
            }
        }

        // Все 3 попытки провалились
        log.error("VK: All 3 upload attempts failed")
        throw lastException ?: IllegalStateException("Failed to upload photo after 3 attempts")
    }

    private fun postTextWithPhotoInternal(message: String?, imageBytes: ByteArray, fileName: String) {
        // Шаг 1: Получаем upload URL
        log.debug("VK: Step 1 - Getting upload server URL")
        val uploadServer = api.get().uri { b ->
            b.path("/photos.getWallUploadServer")
                .queryParam("group_id", groupId)
                .queryParam("access_token", userToken)
                .queryParam("v", apiVersion)
                .build()
        }.retrieve().body(VkUploadServerResponse::class.java)
            ?: error("Empty upload server response")

        uploadServer.error?.let {
            log.error("VK photos.getWallUploadServer error: code={}, msg={}", it.error_code, it.error_msg)
            throw IllegalStateException("VK error ${it.error_code}: ${it.error_msg}")
        }

        val uploadUrl = (uploadServer.response?.get("upload_url") as? String)
            ?: error("No upload_url returned")
        log.debug("VK: Got upload URL: {}", uploadUrl)

        // Шаг 2: Загружаем файл на upload URL
        log.debug("VK: Step 2 - Uploading file to VK servers")
        val resource = object : ByteArrayResource(imageBytes) {
            override fun getFilename(): String = fileName
            override fun contentLength(): Long = imageBytes.size.toLong()
        }

        val multipart: MultiValueMap<String, Any> = LinkedMultiValueMap<String, Any>().apply {
            add("photo", resource)
        }

        val uploadClient = RestClient.builder()
            .baseUrl(uploadUrl)
            .build()

        val uploadResult = try {
            uploadClient.post()
                .uri("")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(multipart)
                .retrieve()
                .body(VkUploadResult::class.java)
                ?: error("Empty upload result")
        } catch (e: Exception) {
            log.error("VK: File upload to VK servers failed", e)
            throw IllegalStateException("Failed to upload file to VK: ${e.message}", e)
        }

        log.debug("VK: Upload result - server={}, hash={}, photo='{}'",
            uploadResult.server, uploadResult.hash, uploadResult.photo.take(50))

        // КРИТИЧЕСКАЯ ПРОВЕРКА: photo должно быть непустым
        if (uploadResult.photo.isBlank()) {
            log.error("VK: Upload result has EMPTY photo field! server={}, hash={}",
                uploadResult.server, uploadResult.hash)
            throw IllegalStateException("VK upload returned empty 'photo' field")
        }

        // Шаг 3: Сохраняем фото через photos.saveWallPhoto
        log.debug("VK: Step 3 - Saving photo via photos.saveWallPhoto")
        val form: MultiValueMap<String, String> = LinkedMultiValueMap<String, String>().apply {
            add("group_id", groupId.toString())
            add("photo", uploadResult.photo)
            add("server", uploadResult.server.toString())
            add("hash", uploadResult.hash)
            add("access_token", userToken)
            add("v", apiVersion)
        }

        log.trace("VK: saveWallPhoto request params - group_id={}, server={}, hash={}, photo_length={}",
            groupId, uploadResult.server, uploadResult.hash, uploadResult.photo.length)

        val saved = try {
            api.post()
                .uri("/photos.saveWallPhoto")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(VkSaveWallPhotoResponse::class.java)
                ?: error("Empty saveWallPhoto response")
        } catch (e: Exception) {
            log.error("VK: photos.saveWallPhoto request failed", e)
            throw IllegalStateException("Failed to save photo: ${e.message}", e)
        }

        saved.error?.let {
            log.error("VK photos.saveWallPhoto error: code={}, msg={} | Params: server={}, hash={}, photo_length={}",
                it.error_code, it.error_msg, uploadResult.server, uploadResult.hash, uploadResult.photo.length)
            throw IllegalStateException("VK error ${it.error_code}: ${it.error_msg}")
        }

        val ph = saved.response?.firstOrNull() ?: error("No photo in saveWallPhoto response")
        val attachment = "photo${ph.owner_id}_${ph.id}"
        log.debug("VK: Photo saved, attachment={}", attachment)

        // Шаг 4: Публикуем пост с фото
        log.debug("VK: Step 4 - Posting to wall with photo attachment")
        val resp = api.get().uri { b ->
            b.path("/wall.post")
                .queryParam("owner_id", -groupId)
                .queryParam("from_group", 1)
                .apply { if (!message.isNullOrBlank()) queryParam("message", message.take(4096)) }
                .queryParam("attachments", attachment)
                .queryParam("access_token", groupToken)
                .queryParam("v", apiVersion)
                .build()
        }.retrieve().body(VkResponse::class.java)

        resp?.error?.let {
            log.error("VK wall.post (with photo) error: code={}, msg={}", it.error_code, it.error_msg)
            throw IllegalStateException("VK error ${it.error_code}: ${it.error_msg}")
        }

        log.info("VK: Photo post published successfully | attachment={}", attachment)
    }
}

data class VkResponse(val response: Any? = null, val error: VkError? = null)
data class VkError(val error_code: Int, val error_msg: String)
data class VkUploadServerResponse(val response: Map<String, Any>? = null, val error: VkError? = null)
data class VkUploadResult(
    val server: Int,
    val photo: String,
    val hash: String,
    val photos_list: String? = null
)
data class VkSaveWallPhotoResponse(val response: List<VkSavedPhoto>? = null, val error: VkError? = null)
data class VkSavedPhoto(val id: Int, val album_id: Int? = null, val owner_id: Int)