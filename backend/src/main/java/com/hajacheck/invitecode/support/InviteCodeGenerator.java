package com.hajacheck.invitecode.support;

import java.security.SecureRandom;

/**
 * 초대 코드 생성(#794) — 프론트 발급 모달과 동일 포맷(6자, XXX-XXX). 혼동하기 쉬운 문자(0/O, 1/I)는 제외한다.
 */
public final class InviteCodeGenerator {

    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int GROUP_LENGTH = 3;
    private static final SecureRandom RANDOM = new SecureRandom();

    private InviteCodeGenerator() {
    }

    public static String generate() {
        return group() + "-" + group();
    }

    private static String group() {
        StringBuilder builder = new StringBuilder(GROUP_LENGTH);
        for (int i = 0; i < GROUP_LENGTH; i++) {
            builder.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        }
        return builder.toString();
    }
}
