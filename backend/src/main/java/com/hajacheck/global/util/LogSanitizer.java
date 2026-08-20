package com.hajacheck.global.util;

import java.util.regex.Pattern;

/**
 * 로그 위조(CWE-117) 방지 — 로그에 찍기 전 <b>사용자 입력에서 유래한 문자열</b>의 제어문자를 제거한다.
 *
 * <p><b>왜 필요한가</b>: Logback 은 파라미터({@code {}})의 제어문자를 이스케이프하지 않는다. 값에 CR/LF 를
 * 실으면 로그 파일에 <b>가짜 로그 라인이 통째로 주입</b>된다 — "존재하지 않는 관리자 조치"를 감사 로그에
 * 심을 수 있다는 뜻이고, 그 로그가 actor 를 담은 유일한 기록이면 부인방지가 정면으로 깨진다.
 * 길이 제한({@code @Size})은 CR/LF 를 막지 못하므로 검증과 별개로 살균이 필요하다.
 *
 * <p>{@code \p{Cntrl}} 은 ASCII 제어문자만 잡으므로, 일부 로그 뷰어가 줄바꿈으로 렌더링하는 유니코드
 * 줄바꿈(U+0085 NEL · U+2028 LINE SEPARATOR · U+2029 PARAGRAPH SEPARATOR)을 함께 포함한다.
 *
 * <p><b>DB 저장분에는 쓰지 않는다</b> — jsonb 병합은 Jackson {@code ObjectNode.put} 이 제어문자를
 * 이스케이프해 저장하므로 원문을 그대로 보존하는 편이 감사에 유리하다. 살균은 <b>로그 경로 전용</b>이다.
 *
 * <p>원래 {@code GlobalExceptionHandler} 안의 package-private 메서드였는데(#330 계열), 같은 방어가
 * 필요한 다른 패키지(플랫폼 관리자 감사 로그 #1367)에서 쓸 수 없어 공용 유틸로 승격했다.
 */
public final class LogSanitizer {

    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}\\u0085\\u2028\\u2029]");

    private LogSanitizer() {
    }

    /** 제어문자를 {@code _} 로 치환한다. null 은 null 그대로(로그에 "null" 로 찍히게 둔다). */
    public static String sanitize(String value) {
        if (value == null) {
            return null;
        }
        return CONTROL_CHARS.matcher(value).replaceAll("_");
    }
}
