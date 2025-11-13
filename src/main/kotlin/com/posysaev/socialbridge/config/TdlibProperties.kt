package com.posysaev.socialbridge.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "telegram-tdlib")
data class TdlibProperties(
    var enabled: Boolean = false,
    var apiId: Int = 0,
    var apiHash: String = "",
    var phoneNumber: String = "",
    var sources: List<String> = emptyList(),
    var code: String? = null,       // одноразовый код (опционально)
    var password: String? = null,   // cloud password, если включён (опц.)
    var dbDir: String? = null,      // директории TDLib (опц.)
    var filesDir: String? = null,
    var targetChatId: Long = 0
)
