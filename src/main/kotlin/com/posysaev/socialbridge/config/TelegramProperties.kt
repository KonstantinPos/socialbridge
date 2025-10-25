package com.posysaev.socialbridge.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "telegram")
data class TelegramProperties(
    var botToken: String,
    var sourceChatIds: Set<Long>,
    var timeoutSec: Int,
    var pollingIntervalMs: Long,
    var baseUrl: String,
    var targetChatId: Long
)
