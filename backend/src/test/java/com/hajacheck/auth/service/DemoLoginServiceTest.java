package com.hajacheck.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hajacheck.auth.config.DemoProperties;
import com.hajacheck.auth.dto.LoginRequest;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.support.StubRateLimiter;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 데모 로그인 게이트(#1626) 단위 테스트 — 비활성 404 / 크레덴셜 미설정 fail-closed /
 * rate-limit 429 / 검사 순서(비활성일 땐 rate-limit 카운터를 소모하지 않음)를 고정한다.
 * 실제 세션 발급·permitAll 배선은 {@code DemoLoginIntegrationTest} 가 검증한다.
 */
class DemoLoginServiceTest {

    private static final String LOGIN_ID = "demo-admin@hajacheck.demo";
    private static final String PASSWORD = "demo-pw1";

    private DemoProperties properties;
    private AtomicInteger rateLimitCalls;

    @BeforeEach
    void setUp() {
        properties = new DemoProperties();
        properties.setLoginId(LOGIN_ID);
        rateLimitCalls = new AtomicInteger();
    }

    private DemoLoginService serviceWithLimiterResult(boolean allowed) {
        return new DemoLoginService(properties, StubRateLimiter.of((key, limit, window) -> {
            rateLimitCalls.incrementAndGet();
            return allowed;
        }));
    }

    @Test
    void 기본값은_비활성이라_404이고_rate_limit_카운터를_소모하지_않는다() {
        // enabled 기본 false — 꺼진 환경에서는 요청량과 무관하게 한결같이 404 여야 기능 존재가 열거되지
        // 않는다(429 가 새면 "꺼져 있지만 존재는 한다"가 드러난다). 그래서 순서 검증이 계약의 일부다.
        DemoLoginService service = serviceWithLimiterResult(true);

        assertThatThrownBy(service::requireAvailable)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AUTH_DEMO_DISABLED);
        assertThat(rateLimitCalls).hasValue(0);
    }

    @Test
    void 스위치는_켰지만_비밀번호가_비어있으면_fail_closed_404다() {
        properties.setEnabled(true);
        // admin-password 기본값 = 빈 문자열(DEMO_ADMIN_PASSWORD 미설정) — 빈 비밀번호로 인증을
        // 시도하거나 500 으로 새지 않고 비활성과 동일하게 404.
        DemoLoginService service = serviceWithLimiterResult(true);

        assertThatThrownBy(service::requireAvailable)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AUTH_DEMO_DISABLED);
        assertThat(rateLimitCalls).hasValue(0);
    }

    @Test
    void 전역_한도_초과면_429다() {
        properties.setEnabled(true);
        properties.setAdminPassword(PASSWORD);
        DemoLoginService service = serviceWithLimiterResult(false);

        assertThatThrownBy(service::requireAvailable)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AUTH_TOO_MANY_REQUESTS);
        assertThat(rateLimitCalls).hasValue(1);
    }

    @Test
    void 활성_상태면_통과하고_서버_보관_크레덴셜로_로그인_요청을_만든다() {
        properties.setEnabled(true);
        properties.setAdminPassword(PASSWORD);
        DemoLoginService service = serviceWithLimiterResult(true);

        service.requireAvailable();
        LoginRequest request = service.demoLoginRequest();

        assertThat(request.loginId()).isEqualTo(LOGIN_ID);
        assertThat(request.password()).isEqualTo(PASSWORD);
    }
}
