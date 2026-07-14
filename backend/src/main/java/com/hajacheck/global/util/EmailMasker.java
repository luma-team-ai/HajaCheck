package com.hajacheck.global.util;

/**
 * 이메일 마스킹 유틸 — 계정 열거·개인정보 노출 방지용(아이디 찾기·가입 응답).
 * 규칙: 로컬파트 앞 최대 4자만 노출 + "***", 도메인은 유지. 예) haja@check.com → haja***@check.com
 */
public final class EmailMasker {

    private static final int MAX_REVEAL = 4;
    private static final String MASK = "***";

    private EmailMasker() {
    }

    public static String mask(String email) {
        if (email == null || email.isBlank()) {
            return email;
        }
        int at = email.indexOf('@');
        // '@' 가 없거나 로컬파트가 비면 전체를 마스킹 처리.
        if (at <= 0) {
            return MASK;
        }
        String local = email.substring(0, at);
        String domain = email.substring(at); // '@' 포함
        int reveal = Math.min(local.length(), MAX_REVEAL);
        return local.substring(0, reveal) + MASK + domain;
    }
}
