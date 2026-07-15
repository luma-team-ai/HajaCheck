package com.hajacheck.core.ai.config;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * AI 서버(FastAPI) 호출용 RestClient 빈 — Spring Boot 3.x 내장 RestClient 사용
 * (WebClient/webflux 의존성 추가 금지, #228 handoff).
 */
@Configuration
@EnableConfigurationProperties(AiServerProperties.class)
public class AiConfig {

    @Bean
    public RestClient aiServerRestClient(AiServerProperties properties) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .withReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactories.get(settings);

        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
