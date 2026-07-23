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
 */
@Configuration
@EnableConfigurationProperties(BizVerifyProperties.class)
public class BizVerifyConfig {

    @Bean
    public RestClient bizVerifyRestClient(BizVerifyProperties properties) {
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
