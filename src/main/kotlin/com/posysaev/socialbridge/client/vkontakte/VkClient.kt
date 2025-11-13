// file: src/main/kotlin/com/posysaev/socialbridge/client/vkontakte/VkClient.kt
package com.posysaev.socialbridge.client.vkontakte

import com.fasterxml.jackson.databind.ObjectMapper
import com.posysaev.socialbridge.config.VkProperties
import com.posysaev.socialbridge.dto.vk.*
import com.posysaev.socialbridge.service.RetryService
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient

/**
 * Универсальный клиент для публикации во ВКонтакте:
 * - текст, фото (1+), видео (1+), микс фото+видео
 * Теперь поддерживает параметрический groupId на вызов.
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

    /** Универсальная публикация одним wall.post. groupId можно переопределить на вызов. */
    fun post(message: String?, media: List<VkMedia> = emptyList(), groupId: Long? = null) {
        val gid = groupId ?: props.groupId

        if (media.isEmpty()) {
            postToWall(gid, message, emptyList()); return
        }

        val attachments = buildList {
            // фото
            media.filterIsInstance<VkMedia.Photo>().forEach {
                add(uploadPhoto(gid, it.bytes, it.fileName))
            }
            // видео
            media.filterIsInstance<VkMedia.Video>().forEach {
                add(uploadVideo(gid, it.bytes, it.fileName, message))
            }
        }

        postToWall(gid, message, attachments)
    }

    private fun postToWall(gid: Long, message: String?, attachments: List<String>) {
        val resp = retry.retry {
            api.get().uri { b ->
                b.path("/wall.post")
                    .queryParam("owner_id", -gid)
                    .queryParam("from_group", 1)
                    .apply { if (!message.isNullOrBlank()) queryParam("message", message.take(4096)) }
                    .apply { if (attachments.isNotEmpty()) queryParam("attachments", attachments.joinToString(",")) }
                    .queryParam("access_token", props.userAccessToken)
                    .queryParam("v", props.apiVersion)
                    .build()
            }.retrieve().body(VkResponse::class.java)
        }
        resp?.error?.let { throw IllegalStateException("VK error ${it.errorCode}: ${it.errorMsg}") }
        log.info("VK: post published to group {} (attachments: {})", gid, attachments.size)
    }

    private fun uploadPhoto(gid: Long, bytes: ByteArray, fileName: String): String {
        val uploadUrl = getPhotoUploadUrl(gid)

        val firstTry = doVkPhotoUpload(uploadUrl, bytes, fileName, perCallSeconds = 90)

        if (firstTry.photo.isBlank()) {
            val info = probeImage(bytes)
            log.warn(
                "VK upload returned empty 'photo'. Will try RGB re-encode. file={}, info={}",
                fileName, info
            )

            val fixedBytes = normalizeToSrgbJpeg(bytes)
            val fixedName = ensureExt(fileName, ".jpg")

            val secondTry = doVkPhotoUpload(uploadUrl, fixedBytes, fixedName, perCallSeconds = 90, attempt = "fix")
            if (secondTry.photo.isBlank()) {
                throw IllegalStateException(
                    "VK upload returned empty 'photo' even after RGB re-encode; " +
                            "server=${secondTry.server}, hash=${secondTry.hash}"
                )
            }
            return saveWallPhoto(gid, secondTry)
        }

        return saveWallPhoto(gid, firstTry)
    }

    private fun getPhotoUploadUrl(gid: Long): String {
        val res = retry.retry {
            api.get().uri { b ->
                b.path("/photos.getWallUploadServer")
                    .queryParam("group_id", gid)
                    .queryParam("access_token", props.userAccessToken)
                    .queryParam("v", props.apiVersion)
                    .build()
            }.retrieve().body(VkUploadServerResponse::class.java)
        }
        res?.error?.let { throw IllegalStateException("VK error ${it.errorCode}: ${it.errorMsg}") }
        return (res?.response?.get("upload_url") as? String) ?: error("No upload_url returned for photo")
    }

    private fun doVkPhotoUpload(
        uploadUrl: String,
        bytes: ByteArray,
        fileName: String,
        perCallSeconds: Long,
        attempt: String = "orig"
    ): VkUploadResult {
        val mediaType = when {
            fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true) -> "image/jpeg"
            fileName.endsWith(".png", true) -> "image/png"
            fileName.endsWith(".gif", true) -> "image/gif"
            else -> "application/octet-stream"
        }.toMediaTypeOrNull()

        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("photo", fileName, bytes.toRequestBody(mediaType))
            .build()

        val request = Request.Builder().url(uploadUrl).post(multipart).build()

        return retry.retry {
            val call = httpClient.newCall(request)
            call.timeout().timeout(perCallSeconds, java.util.concurrent.TimeUnit.SECONDS)

            val host = runCatching { java.net.URI(uploadUrl).host }.getOrNull()
            val sha1 = sha1(bytes).take(12)
            val t0 = System.nanoTime()

            call.execute().use { resp ->
                val dtMs = (System.nanoTime() - t0) / 1_000_000
                val raw = resp.body?.string()

                log.debug(
                    "VK uploadPhoto[{}] resp: host={}, code={}, time={}ms, ct={}, sha1={}",
                    attempt, host, resp.code, dtMs, resp.header("Content-Type"), sha1
                )

                if (!raw.isNullOrBlank()) {
                    log.debug(
                        "VK uploadPhoto[{}] raw[{}]: {}", attempt, sha1,
                        if (raw.length > 2048) raw.take(2048) + "…[truncated]" else raw
                    )
                } else {
                    log.warn("VK uploadPhoto[{}] empty body [sha1={}]", attempt, sha1)
                }

                if (!resp.isSuccessful) {
                    throw IllegalStateException(
                        "VK photo upload failed: HTTP ${resp.code} ${resp.message}. Body: ${raw ?: "<empty>"}"
                    )
                }
                try {
                    objectMapper.readValue(raw, VkUploadResult::class.java)
                } catch (e: Exception) {
                    log.error("Cannot parse VkUploadResult ({}): {}", attempt, raw)
                    throw IllegalStateException("Unexpected photo upload response", e)
                }
            }
        }
    }

    private fun saveWallPhoto(gid: Long, uploadResult: VkUploadResult): String {
        val saved = retry.retry {
            val form = LinkedMultiValueMap<String, String>().apply {
                add("group_id", gid.toString())
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
        log.info("VK: uploaded photo {} to group {}", attach, gid)
        return attach
    }

    private fun uploadVideo(gid: Long, bytes: ByteArray, fileName: String, message: String?): String {
        val saveResp = retry.retry {
            val form = LinkedMultiValueMap<String, String>().apply {
                add("group_id", gid.toString())
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
            .addFormDataPart("video_file", fileName, bytes.toRequestBody(mediaType))
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
            log.info("VK: uploaded video {} to group {}", attach, gid)
            return attach
        }
    }

    private fun ensureExt(name: String, ext: String): String {
        val clean = name.ifBlank { "photo" }
        val hasExt = clean.substringAfterLast('.', missingDelimiterValue = "").isNotBlank()
        return if (hasExt) clean else clean + ext
    }

    private fun sha1(b: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

    private data class ImgInfo(
        val width: Int, val height: Int,
        val numComponents: Int, val colorSpace: String, val type: String
    )

    private fun probeImage(bytes: ByteArray): ImgInfo {
        val img = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes))
            ?: return ImgInfo(0, 0, -1, "unreadable", "unknown")
        val cm = img.colorModel
        val cs = cm.colorSpace
        val csName = when (cs.type) {
            java.awt.color.ColorSpace.TYPE_RGB -> "RGB"
            java.awt.color.ColorSpace.TYPE_CMYK -> "CMYK"
            java.awt.color.ColorSpace.TYPE_GRAY -> "GRAY"
            else -> "TYPE_${cs.type}"
        }
        return ImgInfo(
            img.width, img.height, cm.numComponents, csName, when (img.type) {
                java.awt.image.BufferedImage.TYPE_INT_RGB -> "INT_RGB"
                java.awt.image.BufferedImage.TYPE_3BYTE_BGR -> "3BYTE_BGR"
                java.awt.image.BufferedImage.TYPE_4BYTE_ABGR -> "4BYTE_ABGR"
                else -> "TYPE_${img.type}"
            }
        )
    }

    private fun normalizeToSrgbJpeg(src: ByteArray): ByteArray {
        val img = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(src))
            ?: error("Unsupported/invalid image; cannot normalize to sRGB JPEG")

        // приводим к RGB
        val rgb = java.awt.image.BufferedImage(img.width, img.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = rgb.createGraphics()
        try {
            g.drawImage(img, 0, 0, null)
        } finally {
            g.dispose()
        }

        val baos = java.io.ByteArrayOutputStream()
        val writers = javax.imageio.ImageIO.getImageWritersByFormatName("jpg")
        val writer = writers.next()
        try {
            val ios = javax.imageio.ImageIO.createImageOutputStream(baos)
            writer.output = ios
            val iwp = writer.defaultWriteParam
            writer.write(null, javax.imageio.IIOImage(rgb, null, null), iwp)
            ios.close()
        } finally {
            writer.dispose()
        }
        return baos.toByteArray()
    }
}
