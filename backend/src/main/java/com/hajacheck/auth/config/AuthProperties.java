package com.hajacheck.auth.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 기업 인증 토큰 TTL 설정 — 비밀번호 재설정 토큰/가입 상태 토큰 만료시간.
 * "10m", "30d" 형태 문자열이 Duration 으로 바인딩된다.
 */
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {

    /** 비밀번호 재설정 토큰 TTL. 기본 10분. */
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
