package com.posysaev.socialbridge.client.max

import com.posysaev.socialbridge.client.MessengerClient
import org.springframework.stereotype.Component

@Component
class MaxClient : MessengerClient {
    override fun postToWall(message: String?, photos: List<Pair<ByteArray, String>>) {
        TODO("Not yet implemented")
    }

}
