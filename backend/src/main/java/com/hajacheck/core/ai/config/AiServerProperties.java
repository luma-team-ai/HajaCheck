package com.hajacheck.core.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 서버(FastAPI) 프록시 호출 설정 — SpringBoot_코드_컨벤션.md §9: 매직넘버 금지 → @ConfigurationProperties 바인딩.
 *
 * <p>⚠️ internalKey 는 공유 시크릿이다. 이 클래스는 절대 {@code @ToString}/{@code @Data} 등으로
 * toString() 을 오버라이드하지 않는다(기본 Object#toString 은 필드값을 노출하지 않음) —
 * 로그·예외 메시지에 값이 찍히지 않도록 각별히 주의할 것.
 */
@ConfigurationProperties(prefix = "ai.server")
public class AiServerProperties {

    private String baseUrl;
    private String internalKey;
    // 하자 자연어 검색(HAJA-120) 전용 — X-Internal-Key와 별개 토큰(contract.md InternalServiceToken).
    private String internalServiceToken;
    private long connectTimeoutMs = 3000;
    // HF Inference(Qwen3 reasoning) 정상 응답이 최대 HF_TIMEOUT(기본 120s)까지 걸릴 수 있어
    // application.yml 미설정 시 기본 폴백도 그보다 크게 유지한다(#448 P1). 실제값은 application.yml.
    private long readTimeoutMs = 150000;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getInternalKey() {
        return internalKey;
    }

    public void setInternalKey(String internalKey) {
        this.internalKey = internalKey;
    }

    public String getInternalServiceToken() {
        return internalServiceToken;
    }

    public void setInternalServiceToken(String internalServiceToken) {
        this.internalServiceToken = internalServiceToken;
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
