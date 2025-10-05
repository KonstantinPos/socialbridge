package com.posysaev.socialbridge.config

import org.springframework.boot.context.properties.ConfigurationProperties


@ConfigurationProperties(prefix = "vk")
data class VkProperties(
    var userAccessToken: String,
    var groupId: Long,
    var apiVersion: String,
    var baseUrl: String
)
