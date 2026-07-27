package com.hajacheck.payment.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 토스페이먼츠 결제 승인 호출 설정(#988 / HAJA-489) — SpringBoot_코드_컨벤션.md §9: 매직넘버 금지.
 * {@code BizVerifyProperties}(국세청 진위확인)와 동일한 골격이다.
 *
 * <p>⚠️ {@code secretKey} 는 PG 시크릿이다. 이 클래스는 절대 {@code @ToString}/{@code @Data} 등으로
 * toString() 을 오버라이드하지 않는다(기본 Object#toString 은 필드값을 노출하지 않음) — 로그·예외 메시지에
 * 키가 찍히지 않도록 각별히 주의할 것(보안 요구 6).
 *
 * <p><b>미설정 정책은 fail-close</b>(국세청 진위확인의 fail-open 과 반대다): 키가 없으면 결제 진입점
 * ({@code POST /api/me/plan/orders}·{@code /api/me/payments/confirm})에서 즉시
 * {@link com.hajacheck.global.exception.ErrorCode#PAYMENT_GATEWAY_ERROR} 로 거절한다. 돈이 오가는 흐름은
 * "일단 진행"이 성립하지 않고, 빈 문자열로 Basic 인증을 시도해 PG 4xx 를 받거나 NPE 로 500 이 새는 것보다
 * 도메인 에러로 명확히 실패하는 편이 훨씬 진단 가능하기 때문이다.
 *
 * <p>다만 <b>앱 기동은 막지 않는다</b>(compose 가드가 {@code :-} 소프트인 이유와 세트). 결제는 샌드박스
 * 데모 부가기능이라, 키 누락으로 서비스 전체가 뜨지 못하면 #531 과 같은 형태의 배포 장애가 된다 —
 * 실패 범위를 "결제 기능"으로 국한하는 것이 이 트레이드오프의 결론이다.
 */
@ConfigurationProperties(prefix = "toss-payments")
public class TossPaymentsProperties {

    private String baseUrl;

    /** 토스페이먼츠 시크릿 키(테스트 키 {@code test_sk_...}). 미설정이면 결제 기능만 fail-close 된다. */
    private String secretKey;

    private long connectTimeoutMs = 3000;

    /**
     * 승인 API read-timeout. 승인은 카드사 왕복이 포함돼 조회성 호출보다 느리고, 무엇보다 <b>타임아웃이
     * 곧 "승인 여부 불명"</b>이라 짧게 끊을수록 미확정 결제가 늘어난다. 사용자가 결제창을 닫지 못한 채
     * 기다리는 인터랙티브 경로이므로 10초로 둔다(진위확인 실시간 경로 8초보다 조금 길게).
     */
    private long readTimeoutMs = 10000;

    /** 결제창에 표시할 주문명 접두사 — "{prefix} {PLAN} 플랜 구독" 형태로 조립한다. */
    private String orderNamePrefix = "HajaCheck";

    /** 결제 이력 목록 반환 상한 — 무제한 반환 방지(마이페이지 모달은 최근 이력만 보여준다). */
    private int historyMaxSize = 100;

    /**
     * 사전 등록한 READY 주문의 유효시간(#988 리뷰 P2). 금액이 스냅샷이라 시한이 없으면 <b>요금 인상 직전에
     * 주문을 쟁여두고 나중에 구가격으로 결제</b>할 수 있고, 승인되지 않은 주문이 무한 누적된다.
     *
     * <p>30분은 토스 결제창 자체의 유효시간과 정렬한 값이다 — 결제창이 만료된 뒤 우리 주문만 살아 있어도
     * 어차피 승인할 수 없으므로, 두 시한을 맞춰 "결제창이 살아 있는 동안은 언제나 확정 가능"을 보장한다.
     */
    private Duration orderTtl = Duration.ofMinutes(30);

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
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

    public String getOrderNamePrefix() {
        return orderNamePrefix;
    }

    public void setOrderNamePrefix(String orderNamePrefix) {
        this.orderNamePrefix = orderNamePrefix;
    }

    public int getHistoryMaxSize() {
        return historyMaxSize;
    }

    public void setHistoryMaxSize(int historyMaxSize) {
        this.historyMaxSize = historyMaxSize;
    }

    public Duration getOrderTtl() {
        return orderTtl;
    }

    public void setOrderTtl(Duration orderTtl) {
        this.orderTtl = orderTtl;
    }
}
