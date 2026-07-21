package com.hajacheck.global.util;

/**
 * 이메일 마스킹 유틸 — 계정 열거·개인정보 노출 방지용(아이디 찾기·가입 응답).
 * 규칙: 로컬파트는 첫 1글자만 노출 + "***", 도메인은 host 첫 1글자만 노출 + "***" + ".TLD"(마지막 '.' 기준).
 * 예) haja@check.com → h***@c***.com / gmail.com 서브도메인도 마지막 '.' 기준 host/TLD 분리.
 */
public final class EmailMasker {

    private static final int REVEAL = 1;
    private static final String MASK = "***";

    private EmailMasker() {
    }

    public static String mask(String email) {
        if (email == null || email.isBlank()) {
            return email;
        }
        int at = email.lastIndexOf('@');
        // '@' 가 없거나 로컬파트가 비면 전체를 마스킹 처리.
        if (at <= 0) {
            return MASK;
        }
        String local = email.substring(0, at);
        String domain = email.substring(at + 1);
        if (domain.isBlank()) {
            return MASK;
        }

        String maskedLocal = local.substring(0, Math.min(local.length(), REVEAL)) + MASK;
        String maskedDomain = maskDomain(domain);
        return maskedLocal + "@" + maskedDomain;
    }

    // 도메인은 마지막 '.' 기준으로 host/TLD 분리 후 host 첫 1글자만 노출.
    // '.' 이 없거나 host 가 비는 등 비정상 형식이면 도메인 전체를 마스킹.
    private static String maskDomain(String domain) {
        int dot = domain.lastIndexOf('.');
        if (dot <= 0 || dot == domain.length() - 1) {
            return MASK;
        }
        String host = domain.substring(0, dot);
        String tld = domain.substring(dot + 1);
        return host.substring(0, Math.min(host.length(), REVEAL)) + MASK + "." + tld;
    }
}
