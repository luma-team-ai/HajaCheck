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

    /**
     * 플랫폼 관리자 모니터링(#728) ai-server 헬스체크 전용 RestClient — {@link #aiServerRestClient}는
     * LLM 호출용으로 read-timeout 이 150s(#448)라 헬스체크에 그대로 쓰면 ai-server 지연 시 대시보드가
     * 함께 멈춘다. 같은 base-url 로 짧은 타임아웃(healthCheckTimeoutMs)만 다르게 별도 빈으로 둔다.
     */
    @Bean
    public RestClient aiServerHealthCheckRestClient(AiServerProperties properties) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofMillis(properties.getHealthCheckTimeoutMs()))
                .withReadTimeout(Duration.ofMillis(properties.getHealthCheckTimeoutMs()));
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactories.get(settings);

        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
