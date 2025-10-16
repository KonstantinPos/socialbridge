package com.posysaev.socialbridge.config

import okhttp3.OkHttpClient
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
            .retryOnConnectionFailure(true)
            .callTimeout(java.time.Duration.ofMinutes(3))
            .connectTimeout(java.time.Duration.ofSeconds(20))
            .readTimeout(java.time.Duration.ofMinutes(2))
            .writeTimeout(java.time.Duration.ofMinutes(2))
            .followRedirects(true)
            .build()

}
