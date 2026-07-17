package com.hajacheck.auth.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 기업 인증 토큰 TTL 설정 — 비밀번호 재설정 토큰/가입 상태 토큰 만료시간.
 * "10m", "30d" 형태 문자열이 Duration 으로 바인딩된다.
 */
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {

    /**
     * 비밀번호 재설정 토큰 TTL. 기본 10분 — 메일 링크의 유효시간이자 보조 인덱스 TTL(#194 / HAJA-172).
     * 짧게 둘수록 유출 링크의 악용 창이 좁아진다. 메일 본문 안내 문구도 이 값을 그대로 쓴다.
     */
    private Duration passwordResetTtl = Duration.ofMinutes(10);

    /** 가입 상태 조회 토큰 TTL. 기본 30일. */
    private Duration signupStatusTtl = Duration.ofDays(30);

    public Duration getPasswordResetTtl() {
        return passwordResetTtl;
    }

    public void setPasswordResetTtl(Duration passwordResetTtl) {
        this.passwordResetTtl = passwordResetTtl;
    }

    public Duration getSignupStatusTtl() {
        return signupStatusTtl;
    }

    public void setSignupStatusTtl(Duration signupStatusTtl) {
        this.signupStatusTtl = signupStatusTtl;
    }
}
