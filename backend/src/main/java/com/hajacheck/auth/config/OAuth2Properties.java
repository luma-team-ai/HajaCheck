package com.hajacheck.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 소셜 로그인 성공/실패 후 프론트 리다이렉트 대상.
 * SpringBoot_코드_컨벤션.md §9: 매직넘버 금지 → @ConfigurationProperties 바인딩.
 * 기본값은 로컬(Vite dev) 기준이며 prod 는 application-prod.yml / env 로 override.
 */
@ConfigurationProperties(prefix = "app.oauth2")
public class OAuth2Properties {

    private String successRedirect = "http://localhost:5173/dashboard";
    private String failureRedirect = "http://localhost:5173/login?error=oauth";

    public String getSuccessRedirect() {
        return successRedirect;
    }

    public void setSuccessRedirect(String successRedirect) {
        this.successRedirect = successRedirect;
    }

    public String getFailureRedirect() {
        return failureRedirect;
    }

    public void setFailureRedirect(String failureRedirect) {
        this.failureRedirect = failureRedirect;
    }
}
