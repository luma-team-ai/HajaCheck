package com.hajacheck.auth.service;

import com.hajacheck.auth.config.DemoProperties;
import com.hajacheck.auth.dto.LoginRequest;
import com.hajacheck.auth.support.RateLimiter;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 데모 원클릭 로그인 게이트(#1626) — 인증·세션 발급 자체는 {@code AuthController.authenticateForPortal}
 * 이 담당하고(세션 고정 방어 순서 계약 때문에 웹 계층에 있어야 한다), 이 서비스는 그 앞단의
 * <b>가용성 판정 + rate-limit + 서버 보관 크레덴셜 제공</b>만 맡는다.
 *
 * <p><b>검사 순서에 의미가 있다</b>: 비활성(404) 판정을 rate-limit(429) 보다 앞에 둔다 — 꺼진 환경에서는
 * 어떤 요청량이든 한결같이 404 여야 기능의 존재가 열거되지 않는다(429 가 먼저 새면 "꺼져 있지만 존재는
 * 한다"가 드러난다). 켜진 환경에서는 rate-limit 이 인증 시도(bcrypt 비교 + 세션 발급)보다 앞에 와서
 * 서버 자원 소모를 막는다(PasswordChangeService 의 순서 원칙과 동일).
 *
 * <p><b>크레덴셜은 서버가 보관·주입한다</b> — 프론트는 바디 없는 POST 한 번만 보내고, 데모 계정의
 * loginId/비밀번호는 응답·로그 어디에도 노출되지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoLoginService {

    /** Redis 키 규약 {도메인}:{용도}:{식별자} — 전역 축이라 식별자는 상수 "global". */
    private static final String RATE_LIMIT_GLOBAL_KEY = "rate:demo-login:global";

    private final DemoProperties demoProperties;
    private final RateLimiter rateLimiter;

    /**
     * 데모 로그인 가능 상태를 강제한다 — 비활성(스위치 off 또는 크레덴셜 미설정)이면 404,
     * 전역 rate-limit 초과면 429. 통과하면 아무 일도 하지 않는다.
     */
    public void requireAvailable() {
        if (!demoProperties.isLoginAvailable()) {
            // 스위치는 켰는데 크레덴셜이 비어 있는 설정 실수는 404 로 fail-closed 하되, 운영자가
            // 원인을 특정할 수 있게 로그로만 구분한다(응답으로 구분하면 존재 열거 단서가 된다).
            if (demoProperties.isEnabled()) {
                log.warn("데모 로그인 비활성 — app.demo.enabled=true 이지만 DEMO_ADMIN_PASSWORD 미설정(fail-closed 404)");
            }
            throw new BusinessException(ErrorCode.AUTH_DEMO_DISABLED);
        }
        DemoProperties.LoginRateLimit limit = demoProperties.getLoginRateLimit();
        if (!rateLimiter.tryAcquire(RATE_LIMIT_GLOBAL_KEY, limit.getGlobalLimit(), limit.getGlobalWindow())) {
            log.warn("데모 로그인 rate-limit 초과 — limit={}/{}", limit.getGlobalLimit(), limit.getGlobalWindow());
            throw new BusinessException(ErrorCode.AUTH_TOO_MANY_REQUESTS);
        }
    }

    /**
     * 서버 보관 데모 크레덴셜로 구성한 로그인 요청 — {@code authenticateForPortal} 재사용을 위해
     * 기존 {@link LoginRequest} 형태 그대로 반환한다(호출부는 이 값을 로그·응답에 싣지 않는다).
     */
    public LoginRequest demoLoginRequest() {
        return new LoginRequest(demoProperties.getLoginId(), demoProperties.getAdminPassword());
    }
}
