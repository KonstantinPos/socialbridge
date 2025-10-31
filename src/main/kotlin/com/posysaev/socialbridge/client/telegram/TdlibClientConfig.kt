package com.posysaev.socialbridge.client.telegram

import com.posysaev.socialbridge.config.TdlibProperties
import it.tdlight.client.APIToken
import it.tdlight.client.AuthenticationSupplier
import it.tdlight.client.SimpleTelegramClient
import it.tdlight.client.SimpleTelegramClientFactory
import it.tdlight.client.TDLibSettings
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class TdlibClientConfig(
    private val props: TdlibProperties
) {
    @Bean
    fun tdlibSettings(): TDLibSettings =
        TDLibSettings.create(APIToken(props.apiId, props.apiHash))

    @Bean
    fun simpleTelegramClientFactory(): SimpleTelegramClientFactory =
        SimpleTelegramClientFactory()

    /** Singleton-клиент TDLib, готовый к использованию в любом сервисе */
    @Bean(destroyMethod = "close")
    fun tdlibClient(
        settings: TDLibSettings,
        factory: SimpleTelegramClientFactory
    ): SimpleTelegramClient =
        factory.builder(settings)
            .build(AuthenticationSupplier.user(props.phoneNumber))
}
