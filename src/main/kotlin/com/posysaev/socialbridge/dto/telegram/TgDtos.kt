package com.posysaev.socialbridge.dto.telegram

import com.fasterxml.jackson.annotation.JsonProperty

data class TgUpdatesResponse(val ok: Boolean, val result: List<TgUpdate>)

data class TgUpdate(
    @JsonProperty("update_id") val updateId: Long,
    val message: TgMessage? = null,
    @JsonProperty("channel_post") val channelPost: TgMessage? = null
)

data class TgMessage(
    @JsonProperty("message_id") val messageId: Long,
    val chat: TgChat,
    val text: String? = null,
    val caption: String? = null,
    val photo: List<TgPhotoSize>? = null,

    val entities: List<TgMessageEntity>? = null,
    @JsonProperty("caption_entities") val captionEntities: List<TgMessageEntity>? = null
)

data class TgMessageEntity(
    val type: String,
    val offset: Int,
    val length: Int,
    val url: String? = null
)

data class TgChat(val id: Long, val type: String)

data class TgPhotoSize(
    @JsonProperty("file_id") val fileId: String,
    val width: Int,
    val height: Int
)

data class TgFileResponse(val ok: Boolean, val result: TgFile)

data class TgFile(
    @JsonProperty("file_id") val fileId: String,
    @JsonProperty("file_unique_id") val fileUniqueId: String,
    @JsonProperty("file_size") val fileSize: Long? = null,
    @JsonProperty("file_path") val filePath: String? = null
)
