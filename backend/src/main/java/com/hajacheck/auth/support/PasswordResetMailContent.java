package com.hajacheck.auth.support;

import java.time.Duration;

/**
 * 재설정 메일의 제목·본문(고정 문구) — 실발송/로그 폴백 두 구현이 같은 내용을 쓰도록 한 곳에 둔다.
 *
 * <p>⚠️ <b>사용자 입력을 넣지 않는다</b>. 삽입되는 값은 서버가 만든 링크와 설정된 TTL 뿐이다
 * (제목·본문에 사용자 입력이 들어가면 메일 헤더 인젝션 표면이 생긴다). 평문 본문으로 충분하다.
 */
final class PasswordResetMailContent {

    static final String SUBJECT = "[하자체크] 비밀번호 재설정 안내";

    private PasswordResetMailContent() {
    }

    static String body(String resetLink, Duration ttl) {
        return """
                안녕하세요, 하자체크입니다.

                아래 링크에서 새 비밀번호를 설정해 주세요.

                %s

                이 링크는 발급 후 %d분이 지나면 만료되며, 한 번만 사용할 수 있습니다.
                본인이 요청하지 않았다면 이 메일을 무시해 주세요. 비밀번호는 변경되지 않습니다.
                """.formatted(resetLink, Math.max(1L, ttl.toMinutes()));
    }
}
