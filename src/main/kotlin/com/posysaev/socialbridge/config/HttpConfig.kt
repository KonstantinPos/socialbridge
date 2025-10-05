package com.posysaev.socialbridge.config

import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

@Configuration
class HttpConfig(
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

    @Bean
    fun okHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
}
