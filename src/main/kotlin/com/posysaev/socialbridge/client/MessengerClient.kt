package com.posysaev.socialbridge.client

interface MessengerClient {
    fun postToWall(message: String?, photos: List<Pair<ByteArray, String>> = emptyList())
}
