package com.hajacheck.bizverify.config;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 국세청 사업자등록정보 진위확인(data.go.kr) 전용 RestClient 빈(#596) — core.ai 의 AiConfig 와 동일 패턴.
 * WebClient/webflux 의존성 추가 금지(내장 RestClient 사용).
 *
 * <p><b>빈 2개로 분리(#880, PR #889 P1)</b>: 제출 경로(무인증+rate-limit 없음)와 실시간 진위확인
 * 경로(rate-limit 보호)의 read-timeout이 서로 달라야 해서 RestClient 빈을 분리했다 — 하나로 합치면
 * 더 긴 쪽(실시간용) 타임아웃이 무인증 제출 경로에도 적용돼 스레드 점유 리스크가 커진다. 자세한 근거는
 * {@link BizVerifyProperties#getReadTimeoutMs()}/{@link BizVerifyProperties#getRealtimeReadTimeoutMs()}
 * Javadoc, 사용처는 {@link com.hajacheck.bizverify.service.NtsBusinessVerifyClient} 참고.
 *
 * <p>{@link PendingBusinessReverifyProperties}(#888 PENDING 자동 재검증 배치 설정)도 이 설정 클래스에서
 * 함께 등록한다(스캔 미사용, 명시 등록 — CompanyAuthConfig 패턴과 동일).
 */
@Configuration
@EnableConfigurationProperties({BizVerifyProperties.class, PendingBusinessReverifyProperties.class})
public class BizVerifyConfig {

    /** 제출 경로 전용(회원가입 게이트 {@code validate()} 단독 호출) — read-timeout 5s, 재시도 없음. */
    @Bean
    public RestClient bizVerifySubmitRestClient(BizVerifyProperties properties) {
        return buildRestClient(properties, properties.getReadTimeoutMs());
    }

    /** 실시간 진위확인 경로 전용(#648, {@code verifyRealtime()}) — read-timeout 8s, 재시도 적용. */
    @Bean
    public RestClient bizVerifyRealtimeRestClient(BizVerifyProperties properties) {
        return buildRestClient(properties, properties.getRealtimeReadTimeoutMs());
    }

    private RestClient buildRestClient(BizVerifyProperties properties, long readTimeoutMs) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .withReadTimeout(Duration.ofMillis(readTimeoutMs));
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactories.get(settings);

        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
