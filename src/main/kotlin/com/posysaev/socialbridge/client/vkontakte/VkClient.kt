package com.posysaev.socialbridge.client.vkontakte

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
    private val api = RestClient.builder().baseUrl("https://api.vk.com/method").build()

    fun postText(message: String) {
        val resp = api.get().uri { b ->
            b.path("/wall.post")
                .queryParam("owner_id", -groupId)
                .queryParam("from_group", 1)
                .queryParam("message", message.take(4096))
                .queryParam("access_token", groupToken)
                .queryParam("v", apiVersion)
                .build()
        }.retrieve().body(VkResponse::class.java)

        resp?.error?.let { throw IllegalStateException("VK error ${it.error_code}: ${it.error_msg}") }
    }

    fun postTextWithPhotoOrThrow(message: String?, imageBytes: ByteArray, fileName: String = "photo.jpg") {
        require(userToken.isNotBlank()) {
            "vk.user-access-token is empty; photos.* require a valid USER token"
        }

        val uploadServer = api.get().uri { b ->
            b.path("/photos.getWallUploadServer")
                .queryParam("group_id", groupId)
                .queryParam("access_token", userToken)
                .queryParam("v", apiVersion)
                .build()
        }.retrieve().body(VkUploadServerResponse::class.java)
            ?: error("Empty upload server response")
        uploadServer.error?.let { throw IllegalStateException("VK error ${it.error_code}: ${it.error_msg}") }
        val uploadUrl = (uploadServer.response?.get("upload_url") as? String)
            ?: error("No upload_url returned")

        val resource = object : ByteArrayResource(imageBytes) {
            override fun getFilename(): String = fileName
            override fun contentLength(): Long = imageBytes.size.toLong()
        }
        val multipart: MultiValueMap<String, Any> = LinkedMultiValueMap<String, Any>().apply {
            add("photo", resource)
        }
        val uploadResult = RestClient.create(uploadUrl)
            .post()
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(multipart)
            .retrieve()
            .body(VkUploadResult::class.java)
            ?: error("Empty upload result")

        val form: MultiValueMap<String, String> = LinkedMultiValueMap<String, String>().apply {
            add("group_id", groupId.toString())
            add("photo", uploadResult.photo)
            add("server", uploadResult.server.toString())
            add("hash", uploadResult.hash)
            add("access_token", userToken)
            add("v", apiVersion)
        }
        val saved = api.post()
            .uri("/photos.saveWallPhoto")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(VkSaveWallPhotoResponse::class.java)
            ?: error("Empty saveWallPhoto response")
        saved.error?.let { throw IllegalStateException("VK error ${it.error_code}: ${it.error_msg}") }

        val ph = saved.response?.firstOrNull() ?: error("No photo in saveWallPhoto response")
        val attachment = "photo${ph.owner_id}_${ph.id}"

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

        resp?.error?.let { throw IllegalStateException("VK error ${it.error_code}: ${it.error_msg}") }
    }
}

data class VkResponse(val response: Any? = null, val error: VkError? = null)
data class VkError(val error_code: Int, val error_msg: String)
data class VkUploadServerResponse(val response: Map<String, Any>? = null, val error: VkError? = null)
data class VkUploadResult(val server: Int, val photo: String, val hash: String)
data class VkSaveWallPhotoResponse(val response: List<VkSavedPhoto>? = null, val error: VkError? = null)
data class VkSavedPhoto(val id: Int, val album_id: Int? = null, val owner_id: Int)
