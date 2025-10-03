package com.posysaev.socialbridge.dto.vk

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Универсальный ответ от VK API
 */
data class VkResponse(
    /** Тело успешного ответа (может быть любым JSON в зависимости от метода) */
    val response: Any? = null,
    /** Ошибка, если произошёл сбой */
    val error: VkError? = null
)

/**
 * Ошибка от VK API
 */
data class VkError(
    /** Код ошибки (см. документацию VK API) */
    @JsonProperty("error_code")
    val errorCode: Int,

    /** Сообщение об ошибке */
    @JsonProperty("error_msg")
    val errorMsg: String
)

/**
 * Ответ на запрос photos.getWallUploadServer
 */
data class VkUploadServerResponse(
    /** Данные с upload_url */
    val response: Map<String, Any>? = null,
    /** Ошибка, если есть */
    val error: VkError? = null
)

/**
 * Результат загрузки файла на сервер VK
 */
data class VkUploadResult(
    /** ID сервера, куда загружено фото */
    val server: Int,

    /** JSON-строка с данными о фото (может быть пустой при сбое) */
    val photo: String,

    /** Хэш загрузки */
    val hash: String,

    /** Список фото (опционально, чаще null) */
    @JsonProperty("photos_list")
    val photosList: String? = null
)

/**
 * Ответ метода photos.saveWallPhoto
 */
data class VkSaveWallPhotoResponse(
    /** Список сохранённых фото */
    val response: List<VkSavedPhoto>? = null,
    /** Ошибка при сохранении */
    val error: VkError? = null
)

/**
 * Описание сохранённого фото в VK
 */
data class VkSavedPhoto(
    /** ID фото */
    val id: Int,

    /** ID альбома, если применимо */
    @JsonProperty("album_id")
    val albumId: Int? = null,

    /** ID владельца фото (обычно группа или пользователь) */
    @JsonProperty("owner_id")
    val ownerId: Int
)
