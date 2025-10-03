package com.posysaev.socialbridge.dto.telegram

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Ответ Telegram API на getUpdates
 */
data class TgUpdatesResponse(
    /** Успешность запроса */
    val ok: Boolean,
    /** Список обновлений (update_id + сообщения) */
    val result: List<TgUpdate>
)

/**
 * Обновление Telegram (сообщение или пост канала)
 */
data class TgUpdate(
    /** Уникальный ID обновления */
    @JsonProperty("update_id")
    val updateId: Long,

    /** Сообщение из чата (если есть) */
    val message: TgMessage? = null,

    /** Сообщение из канала (если есть) */
    @JsonProperty("channel_post")
    val channelPost: TgMessage? = null
)

/**
 * Сообщение в Telegram
 */
data class TgMessage(
    /** ID сообщения */
    @JsonProperty("message_id")
    val messageId: Long,

    /** Чат, откуда пришло сообщение */
    val chat: TgChat,

    /** Текст сообщения */
    val text: String? = null,

    /** Подпись (для фото, видео и т.д.) */
    val caption: String? = null,

    /** Список размеров фото */
    val photo: List<TgPhotoSize>? = null,

    /** Энтити в тексте (ссылки, жирный, курсив и т.д.) */
    val entities: List<TgMessageEntity>? = null,

    /** Энтити в подписи */
    @JsonProperty("caption_entities")
    val captionEntities: List<TgMessageEntity>? = null
)

/**
 * Спец. сущность в тексте (ссылка, bold и т.д.)
 */
data class TgMessageEntity(
    /** Тип сущности (например, "text_link") */
    val type: String,
    /** Смещение от начала текста */
    val offset: Int,
    /** Длина текста, к которому применяется */
    val length: Int,
    /** Ссылка (если type = text_link) */
    val url: String? = null
)

/**
 * Информация о чате
 */
data class TgChat(
    /** Уникальный ID чата */
    val id: Long,
    /** Тип чата (private, group, supergroup, channel) */
    val type: String
)

/**
 * Размер фото (одно изображение в разных разрешениях)
 */
data class TgPhotoSize(
    /** ID файла */
    @JsonProperty("file_id")
    val fileId: String,
    /** Ширина */
    val width: Int,
    /** Высота */
    val height: Int
)

/**
 * Ответ на запрос getFile
 */
data class TgFileResponse(
    /** Успешность */
    val ok: Boolean,
    /** Данные о файле */
    val result: TgFile
)

/**
 * Информация о файле Telegram
 */
data class TgFile(
    /** ID файла */
    @JsonProperty("file_id")
    val fileId: String,

    /** Уникальный ID файла */
    @JsonProperty("file_unique_id")
    val fileUniqueId: String,

    /** Размер файла в байтах */
    @JsonProperty("file_size")
    val fileSize: Long? = null,

    /** Путь для скачивания через /file/bot<token>/<file_path> */
    @JsonProperty("file_path")
    val filePath: String? = null
)
