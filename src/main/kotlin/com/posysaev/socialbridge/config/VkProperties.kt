package com.posysaev.socialbridge.config

import org.springframework.boot.context.properties.ConfigurationProperties


@ConfigurationProperties(prefix = "vk")
data class VkProperties(
    var userAccessToken: String,
    var groupId: Long,
    var apiVersion: String,
    var baseUrl: String,
    val routes: List<Route> = emptyList()
) {
    data class Route(val tgChatId: Long, val groupId: Long)
    val routeMap: Map<Long, Long> by lazy { routes.associate { it.tgChatId to it.groupId } }
}
