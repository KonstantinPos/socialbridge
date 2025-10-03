package com.posysaev.socialbridge.client.max

import com.posysaev.socialbridge.client.MessengerClient
import org.springframework.stereotype.Component

@Component
class MaxClient : MessengerClient {
    override fun postText(message: String) {
        // TODO: реализовать интеграцию с MAX
    }

    override fun postTextWithPhoto(message: String?, imageBytes: ByteArray, fileName: String) {
        // TODO: реализовать интеграцию с MAX
    }
}
