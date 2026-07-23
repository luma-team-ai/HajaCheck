package com.hajacheck.bizverify.config;

import java.time.Duration;
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

    /** 실시간 진위확인 공개 API(#648) rate-limit. */
    private RateLimit rateLimit = new RateLimit();

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

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    /**
     * 실시간 진위확인 공개 API(#648, {@code POST /api/auth/business-verification}) rate-limit 설정 —
     * 비로그인(가입 전) 엔드포인트라 {@code BusinessLicenseOcrRateLimit}(AuthProperties)와 동일하게
     * 사용자 축이 없다. 축은 <b>전역 상한</b> 둘(분당 + 일일)뿐이다.
     *
     * <p>⚠️ <b>IP 축을 쓰지 않는 이유는 {@code RateLimiter} 인터페이스 javadoc과 동일</b>(2026-07-17 A
     * 결정) — host nginx 가 {@code X-Forwarded-For} 에 클라 제공값을 덧붙이고 스프링이 첫 항목을 클라
     * IP 로 채택해 헤더 위조로 무력화되며, 실제 엣지가 레포 밖 host nginx 라 레포만 고쳐선 완결되지
     * 않는다. 이 레포의 다른 비로그인 공개 엔드포인트(OCR·비밀번호 재설정)도 모두 이 결정을 따른다.
     *
     * <p><b>일일 캡이 특히 중요한 이유</b>: 이 엔드포인트가 호출하는 국세청 status+validate API는
     * data.go.kr 발급 서비스키를 실 회원가입 플로우({@code CompanySignupService})와 <b>공유</b>한다.
     * 이 비로그인 API가 남용되면 그 키의 일일 트래픽 쿼터를 소진해 실제 가입자의 진위확인까지
     * fail-open(SKIPPED)으로 밀어낼 수 있다 — 분당 캡만으로는 지속 반복 시 총량이 무제한이라 일일
     * 절대 캡을 반드시 둔다(BusinessLicenseOcrRateLimit과 동일 기조).
     */
    public static class RateLimit {

        /** 전역 상한(전체 합산) 허용 횟수. 기본 분당 10회 — 실사용(가입 전 진위확인 버튼 클릭)엔 여유. */
        private int globalLimit = 10;

        /** 전역 상한 창 길이. */
        private Duration globalWindow = Duration.ofMinutes(1);

        /** 일일 절대 캡 — 공유 서비스키의 국세청 API 일일 쿼터 소진(=실 가입 fail-open 전이) 방지. */
        private int dailyLimit = 300;

        /** 일일 캡 창 길이. */
        private Duration dailyWindow = Duration.ofDays(1);

        public int getGlobalLimit() {
            return globalLimit;
        }

        public void setGlobalLimit(int globalLimit) {
            this.globalLimit = globalLimit;
        }

        public Duration getGlobalWindow() {
            return globalWindow;
        }

        public void setGlobalWindow(Duration globalWindow) {
            this.globalWindow = globalWindow;
        }

        public int getDailyLimit() {
            return dailyLimit;
        }

        public void setDailyLimit(int dailyLimit) {
            this.dailyLimit = dailyLimit;
        }

        public Duration getDailyWindow() {
            return dailyWindow;
        }

        public void setDailyWindow(Duration dailyWindow) {
            this.dailyWindow = dailyWindow;
        }
    }
}
