package com.hajacheck.auth.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 라이브 데모(기업 ADMIN 원클릭 로그인, #1626) 설정.
 *
 * <p><b>기본값은 전부 꺼짐(fail-closed)</b> — {@code enabled=false} 면 {@code POST /api/auth/demo-login}
 * 은 404({@code AUTH_DEMO_DISABLED})로 응답해 기능의 존재 자체를 드러내지 않는다. 시드
 * ({@code app.demo-seed.enabled})·리셋 스케줄러({@code hajacheck.demo.reset.enabled})의 스위치는 별도다.
 *
 * <p><b>크레덴셜 취급</b>: 데모 계정 비밀번호는 yml 커밋값이 아니라 환경변수
 * {@code ${DEMO_ADMIN_PASSWORD:}} 주입이다(§9 시크릿 규칙). 값이 비어 있으면 데모 로그인은 스위치가
 * 켜져 있어도 비활성과 동일하게 404 로 fail-closed 한다({@link #isLoginAvailable()}) — 빈 비밀번호로
 * 인증을 시도하거나, 크레덴셜 미설정 상태를 500 으로 새게 두지 않는다.
 *
 * <p><b>데모 계정 식별은 loginId(email) 매칭</b>이다 — 스키마 변경(전용 컬럼) 없이 설정 기반으로
 * 판별한다(#1626 설계 결정). 가드({@code DemoAccountGuard})와 {@code GET /api/users/me} 의
 * {@code isDemo} 계산이 모두 {@link #isDemoLoginId(String)} 를 쓴다.
 */
@ConfigurationProperties(prefix = "app.demo")
public class DemoProperties {

    /** 데모 로그인 기능 스위치. 기본 false — 켜지 않은 환경에서는 엔드포인트가 404 다. */
    private boolean enabled = false;

    /**
     * 데모 계정 loginId(email). 시더가 이 값으로 계정을 만들고, 로그인·가드·isDemo 판별이 모두
     * 이 값 하나를 기준으로 한다. 예약 도메인(.demo)이라 실제 메일함이 존재하지 않는다 —
     * 비밀번호 재설정 메일 링크가 배달될 곳이 없어 메일 기반 탈취 표면이 생기지 않는다.
     */
    private String loginId = "demo-admin@hajacheck.demo";

    /**
     * 데모 계정 비밀번호 — 환경변수 {@code DEMO_ADMIN_PASSWORD} 주입(기본 빈 문자열 = 비활성).
     * ⚠️ 이 값은 로그·응답·예외 메시지 어디에도 남기지 않는다.
     */
    private String adminPassword = "";

    /** 데모 로그인 rate-limit — 전역 축 하나(IP 축 금지: RateLimiter javadoc, 2026-07-17 A 결정). */
    private LoginRateLimit loginRateLimit = new LoginRateLimit();

    /**
     * 전역 축 하나만 두는 이유: 비로그인·무크레덴셜 엔드포인트라 사용자 축이 존재하지 않고, IP 축은
     * 프로젝트 결정으로 금지다(XFF 위조 — BusinessLicenseOcrRateLimit 과 동일 상황). 방어 대상은
     * 특정 사용자가 아니라 "세션 발급 + Redis 세션 저장"이라는 서버 자원이다. 기본 분당 10회 —
     * 시연·데모 방문 트래픽(동시 한 자릿수)은 통과하되 세션 폭탄은 막는 선.
     */
    public static class LoginRateLimit {

        private int globalLimit = 10;

        private Duration globalWindow = Duration.ofMinutes(1);

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
    }

    /** 데모 로그인이 실제로 성립 가능한 상태인가 — 스위치 on + 크레덴셜 설정됨(둘 다 필요, fail-closed). */
    public boolean isLoginAvailable() {
        return enabled && adminPassword != null && !adminPassword.isBlank();
    }

    /** 설정 기반 데모 계정 판별 — 스키마 변경 없이 loginId(email) 매칭 하나로 통일(#1626). */
    public boolean isDemoLoginId(String email) {
        return email != null && email.equalsIgnoreCase(loginId);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getLoginId() {
        return loginId;
    }

    public void setLoginId(String loginId) {
        this.loginId = loginId;
    }

    public String getAdminPassword() {
        return adminPassword;
    }

    public void setAdminPassword(String adminPassword) {
        this.adminPassword = adminPassword;
    }

    public LoginRateLimit getLoginRateLimit() {
        return loginRateLimit;
    }

    public void setLoginRateLimit(LoginRateLimit loginRateLimit) {
        this.loginRateLimit = loginRateLimit;
    }
}
