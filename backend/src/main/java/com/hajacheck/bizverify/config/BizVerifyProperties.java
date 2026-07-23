package com.hajacheck.bizverify.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 국세청 사업자등록정보 진위확인(data.go.kr) 호출 설정(#596) — SpringBoot_코드_컨벤션.md §9: 매직넘버 금지.
 *
 * <p>⚠️ {@code serviceKey} 는 data.go.kr 발급 인증키(시크릿)다. 이 클래스는 절대
 * {@code @ToString}/{@code @Data} 등으로 toString() 을 오버라이드하지 않는다(기본 Object#toString 은
 * 필드값을 노출하지 않음) — 로그·예외 메시지에 키가 찍히지 않도록 각별히 주의할 것.
 *
 * <p>serviceKey 는 data.go.kr 의 "일반 인증키(Decoding)" 를 넣는다. 미설정(빈 문자열)이면
 * 진위확인을 스킵하고 가입을 그대로 진행한다(fail-open — {@link com.hajacheck.bizverify.service.NtsBusinessVerifyClient}).
 */
@ConfigurationProperties(prefix = "biz-verify")
public class BizVerifyProperties {

    private String baseUrl;
    private String serviceKey;
    private long connectTimeoutMs = 3000;
    private long readTimeoutMs = 5000;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getServiceKey() {
        return serviceKey;
    }

    public void setServiceKey(String serviceKey) {
        this.serviceKey = serviceKey;
    }

    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public long getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(long readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }
}
