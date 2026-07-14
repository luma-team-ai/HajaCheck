package com.hajacheck.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 약관·개인정보 처리방침 버전 — 서버 소유(클라이언트가 전송하지 않음, 계약 §기업 인증).
 * 동의 이력(UserConsent)에 이 버전을 기록한다.
 */
@ConfigurationProperties(prefix = "app.policy")
public class PolicyProperties {

    private String termsVersion = "1.0";
    private String privacyVersion = "1.0";

    public String getTermsVersion() {
        return termsVersion;
    }

    public void setTermsVersion(String termsVersion) {
        this.termsVersion = termsVersion;
    }

    public String getPrivacyVersion() {
        return privacyVersion;
    }

    public void setPrivacyVersion(String privacyVersion) {
        this.privacyVersion = privacyVersion;
    }
}
