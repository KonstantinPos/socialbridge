package com.posysaev.socialbridge.client.vkontakte

import com.posysaev.socialbridge.client.MessengerClient
import com.posysaev.socialbridge.config.VkProperties
import com.posysaev.socialbridge.dto.vk.VkResponse
import com.posysaev.socialbridge.dto.vk.VkSaveWallPhotoResponse
import com.posysaev.socialbridge.dto.vk.VkUploadResult
import com.posysaev.socialbridge.dto.vk.VkUploadServerResponse
import com.posysaev.socialbridge.service.RetryService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient

@Component
class VkClient(
    private val props: VkProperties,
    @Qualifier("vkRestClient")
    private val api: RestClient,
    private val retry: RetryService
) : MessengerClient {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Универсальный метод публикации.
     * @param message текст поста (может быть null)
     * @param photos список пар (байты, имя файла); пустой — просто текст
     */
    override fun postToWall(message: String?, photos: List<Pair<ByteArray, String>>) {
        if (photos.isEmpty()) {
            postTextOnly(message)
        } else {
            postWithPhotos(message, photos)
        }
    }

    private fun postTextOnly(message: String?) {
        log.info("VK: posting text-only message")
        val resp = retry.retry {
            api.get().uri { b ->
                b.path("/wall.post")
                    .queryParam("owner_id", -props.groupId)
                    .queryParam("from_group", 1)
                    .apply { if (!message.isNullOrBlank()) queryParam("message", message.take(4096)) }
                    .queryParam("access_token", props.userAccessToken)
                    .queryParam("v", props.apiVersion)
                    .build()
            }.retrieve().body(VkResponse::class.java)
        }

        resp?.error?.let { throw IllegalStateException("VK error ${it.errorCode}: ${it.errorMsg}") }
        log.info("VK: Text post published successfully")
    }

    private fun postWithPhotos(message: String?, photos: List<Pair<ByteArray, String>>) {
        require(props.userAccessToken.isNotBlank()) {
            "vk.user-access-token is empty; photos.* require a valid USER token"
        }

        val attachments = mutableListOf<String>()

        for ((bytes, fileName) in photos) {
            val uploadUrl = retry.retry {
                val uploadServer = api.get().uri { b ->
                    b.path("/photos.getWallUploadServer")
                        .queryParam("group_id", props.groupId)
                        .queryParam("access_token", props.userAccessToken)
                        .queryParam("v", props.apiVersion)
                        .build()
                }.retrieve().body(VkUploadServerResponse::class.java)
                    ?: error("Empty upload server response")
                uploadServer.error?.let {
                    throw IllegalStateException("VK error ${it.errorCode}: ${it.errorMsg}")
                }
                (uploadServer.response?.get("upload_url") as? String)
                    ?: error("No upload_url returned")
            }

            val uploadResult = retry.retry {
                val resource = object : ByteArrayResource(bytes) {
                    override fun getFilename() = fileName
                    override fun contentLength() = bytes.size.toLong()
                }
                val multipart = LinkedMultiValueMap<String, Any>().apply { add("photo", resource) }
                val uploadClient = RestClient.builder().baseUrl(uploadUrl).build()
                uploadClient.post().contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(multipart).retrieve().body(VkUploadResult::class.java)
                    ?: error("Empty upload result")
            }

            if (uploadResult.photo.isBlank())
                error("VK upload returned empty 'photo' field")

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
                    .body(form).retrieve().body(VkSaveWallPhotoResponse::class.java)
                    ?: error("Empty saveWallPhoto response")
            }

            saved.error?.let {
                throw IllegalStateException("VK error ${it.errorCode}: ${it.errorMsg}")
            }
            val ph = saved.response?.firstOrNull() ?: error("No photo in saveWallPhoto response")
            attachments += "photo${ph.ownerId}_${ph.id}"
        }

        val resp = retry.retry {
            api.get().uri { b ->
                b.path("/wall.post")
                    .queryParam("owner_id", -props.groupId)
                    .queryParam("from_group", 1)
                    .apply { if (!message.isNullOrBlank()) queryParam("message", message.take(4096)) }
                    .queryParam("attachments", attachments.joinToString(","))
                    .queryParam("access_token", props.userAccessToken)
                    .queryParam("v", props.apiVersion)
                    .build()
            }.retrieve().body(VkResponse::class.java)
        }

        resp?.error?.let {
            throw IllegalStateException("VK error ${it.errorCode}: ${it.errorMsg}")
        }

        log.info("VK: Post with ${attachments.size} photo(s) published successfully")
    }
}
