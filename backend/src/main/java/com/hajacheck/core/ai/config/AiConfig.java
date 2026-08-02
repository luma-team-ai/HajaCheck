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

    /**
     * RAG 임베딩 완료 확인(#1393, RagEmbeddingCompletionPoller/RagEmbeddingStaleReconciler) 전용
     * RestClient — {@link #aiServerRestClient}(LLM용 150s)를 그대로 쓰면 폴러가 시도당 최대 150s씩
     * 붙잡히고(총 최대 25분), 리컨사일러는 다른 {@code @Scheduled} 배치와 공유하는 스레드를 오래
     * 점유한다(PR머신 리뷰 P1). embedding-status는 Chroma 메타데이터 조회뿐인 가벼운 엔드포인트라
     * 짧은 타임아웃(embeddingStatusTimeoutMs)으로 충분하다.
     */
    @Bean
    public RestClient aiServerEmbeddingStatusRestClient(AiServerProperties properties) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .withReadTimeout(Duration.ofMillis(properties.getEmbeddingStatusTimeoutMs()));
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactories.get(settings);

        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
