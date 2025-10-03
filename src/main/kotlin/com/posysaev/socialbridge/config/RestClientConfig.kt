package com.posysaev.socialbridge.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

@Configuration
class RestClientConfig(
    private val telegramProperties: TelegramProperties,
    private val vkProperties: VkProperties
) {
    @Bean
    fun telegramRestClient(): RestClient =
        RestClient.builder()
            .baseUrl(telegramProperties.baseUrl)
            .build()

    @Bean
    fun vkRestClient(): RestClient =
        RestClient.builder()
            .baseUrl(vkProperties.baseUrl)
            .build()
}
