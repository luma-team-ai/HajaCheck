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
    // 플랫폼 관리자 모니터링(#728) ai-server 헬스체크 전용 타임아웃 — 위 readTimeoutMs(150s)를 그대로
    // 쓰면 ai-server가 느릴 때 대시보드 요청이 150초씩 멈춘다. 헬스체크는 짧게 끊고 DOWN 으로 표시한다.
    private long healthCheckTimeoutMs = 3000;
    // RAG 임베딩 완료 확인(#1393) 전용 — embedding-status는 Chroma 메타데이터 조회뿐인 가벼운
    // 엔드포인트라 LLM용 readTimeoutMs(150s)를 그대로 쓰면 안 된다(PR머신 리뷰 P1, 폴러/리컨사일러가
    // 장시간 스레드를 점유하는 원인).
    private long embeddingStatusTimeoutMs = 5000;
    /**
     * 사업자등록증 대조 게이트(#1469, {@code BusinessLicenseCrossCheckService}) 전용 read timeout.
     *
     * <p><b>왜 별도 빈·별도 값인가</b>: 이 호출은 {@code POST /api/auth/companies}(회원가입 제출) 요청
     * 안에서 동기로 일어난다. 이 엔드포인트는 permitAll + rate-limit 미적용이라 요청당 대기시간이 늘면
     * 그대로 Tomcat 워커 스레드 고갈로 이어진다 — 그래서 이 레포는 같은 경로의 국세청 호출을
     * connect 3s + read 5s 로 <b>동결</b>해 두었다({@code BizVerifyProperties#readTimeoutMs}, #889 P1).
     * LLM용 {@link #readTimeoutMs}(150s)를 그대로 물리면 제출 1건의 최악 대기가 약 8s → 약 161s 로
     * 20배 늘어나 그 결정을 정면으로 되돌리게 된다(#728·#1393 에서 이미 두 번 P1으로 잡힌 패턴 —
     * 정답은 "짧은 타임아웃 전용 RestClient 빈").
     *
     * <p><b>15초 산정 근거</b>: ①상한 — connect 3s 를 더해 이 단계 최악 18s, 국세청 8s 와 합쳐 제출 경로
     * 최악 약 26s 로 묶인다(워커 스레드 점유가 유한하게 유지되는 선). ②하한 — 너무 짧으면 정상 응답이
     * 전부 타임아웃(→ {@code OCR_FAILED} fail-open)으로 떨어져 게이트가 무증상으로 꺼진다. 등록증 1장
     * OCR(RapidOCR+경량 LLM)은 리포트 생성 같은 장문 추론과 달리 초 단위로 끝나는 작업이라, 허용 범위
     * (10~15s) 안에서 <b>가장 관대한 값</b>을 골라 정상 완주 확률을 최대화했다.
     *
     * <p>⚠️ 운영에서 {@code OCR_FAILED} 비율이 높으면 이 값을 올리기 전에 <b>AI 서버 p95 지연부터</b>
     * 확인할 것 — 이 값을 키우는 것은 무인증 경로의 스레드 점유를 늘리는 결정이다.
     */
    private long businessLicenseCrossCheckTimeoutMs = 15000;

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

    public long getHealthCheckTimeoutMs() {
        return healthCheckTimeoutMs;
    }

    public void setHealthCheckTimeoutMs(long healthCheckTimeoutMs) {
        this.healthCheckTimeoutMs = healthCheckTimeoutMs;
    }

    public long getBusinessLicenseCrossCheckTimeoutMs() {
        return businessLicenseCrossCheckTimeoutMs;
    }

    public void setBusinessLicenseCrossCheckTimeoutMs(long businessLicenseCrossCheckTimeoutMs) {
        this.businessLicenseCrossCheckTimeoutMs = businessLicenseCrossCheckTimeoutMs;
    }

    public long getEmbeddingStatusTimeoutMs() {
        return embeddingStatusTimeoutMs;
    }

    public void setEmbeddingStatusTimeoutMs(long embeddingStatusTimeoutMs) {
        this.embeddingStatusTimeoutMs = embeddingStatusTimeoutMs;
    }
}
