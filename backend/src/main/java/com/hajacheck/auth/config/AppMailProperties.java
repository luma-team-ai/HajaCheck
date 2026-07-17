package com.hajacheck.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 메일 발송 관련 앱 설정(발신 주소·링크 base). SMTP 접속 정보 자체는 스프링 표준 {@code spring.mail.*} 이 갖는다.
 *
 * <p>⚠️ 시크릿 아님 — 값은 env 참조({@code MAIL_FROM}, {@code FRONTEND_BASE_URL})이고 커밋된 실제 값은 없다.
 */
@ConfigurationProperties(prefix = "app.mail")
public class AppMailProperties {

    /** 발신 주소({@code MAIL_FROM}). 운영은 compose {@code :?} 가드로 강제된다. */
    private String from = "";

    /**
     * 재설정 링크 base — {@code {FRONTEND_BASE_URL}/reset-password?token=...}.
     *
     * <p>⚠️ <b>반드시 설정값만 쓴다.</b> {@code ServletUriComponentsBuilder.fromCurrentRequest()} 나
     * {@code Host}/{@code X-Forwarded-Host} 헤더에서 유도하면 안 된다 — nginx 가 {@code Host} 를 그대로
     * 통과시키므로, 공격자가 Host 를 조작해 <b>피해자 메일에 공격자 도메인 링크</b>를 심는
     * password-reset poisoning 이 성립한다(피해자가 링크를 누르면 토큰이 공격자에게 전달).
     */
    private String frontendBaseUrl = "http://localhost:5173";

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getFrontendBaseUrl() {
        return frontendBaseUrl;
    }

    public void setFrontendBaseUrl(String frontendBaseUrl) {
        this.frontendBaseUrl = frontendBaseUrl;
    }
}
