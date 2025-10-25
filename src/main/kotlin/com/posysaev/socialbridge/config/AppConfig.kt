package com.posysaev.socialbridge.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(
    value = [TelegramProperties::class, VkProperties::class, TdlibProperties::class]
)
class AppConfig
