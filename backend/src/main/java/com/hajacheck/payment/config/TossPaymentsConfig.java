package com.hajacheck.payment.config;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 토스페이먼츠 결제 승인 전용 RestClient 빈(#988 / HAJA-489) — {@code BizVerifyConfig} 와 동일 패턴.
 * WebClient/webflux 의존성 추가 금지(내장 RestClient 사용).
 *
 * <p>빈 이름을 한정자로 구분한다({@code tossPaymentsRestClient}) — 이 프로젝트에는 외부 연동별로 타임아웃이
 * 다른 RestClient 빈이 여럿 있어 타입만으로는 주입이 모호해진다.
 */
@Configuration
@EnableConfigurationProperties(TossPaymentsProperties.class)
public class TossPaymentsConfig {

    @Bean
    public RestClient tossPaymentsRestClient(TossPaymentsProperties properties) {
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
