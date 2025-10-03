package com.posysaev.socialbridge.client

interface MessengerClient {
    fun postText(message: String)
    fun postTextWithPhoto(message: String?, imageBytes: ByteArray, fileName: String = "photo.jpg")
}
