package com.hajacheck.global.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 로그 위조(CWE-117) 살균 유틸 — {@code GlobalExceptionHandler} 안에 있던 규칙을 공용화한 것이라
 * 그쪽 단위 테스트와 같은 계약을 여기서도 고정한다(승격 과정에서 규칙이 바뀌지 않았음을 보장).
 */
class LogSanitizerTest {

    @Test
    @DisplayName("CR/LF·탭 등 제어문자를 제거해 가짜 로그 라인 주입을 막는다")
    void 제어문자를_제거한다() {
        String malicious = "정상 사유\r\n2026-08-20 WARN 위조된-감사-라인\tTAB";

        String sanitized = LogSanitizer.sanitize(malicious);

        assertThat(sanitized).doesNotContain("\r").doesNotContain("\n").doesNotContain("\t");
        assertThat(sanitized).isEqualTo("정상 사유__2026-08-20 WARN 위조된-감사-라인_TAB");
    }

    @Test
    @DisplayName("일부 로그 뷰어가 개행으로 렌더링하는 유니코드 줄바꿈도 제거한다")
    void 유니코드_줄바꿈도_제거한다() {
        // \p{Cntrl} 는 ASCII 만 잡으므로 U+0085(NEL)/U+2028/U+2029 를 명시적으로 포함해야 한다.
        // 비가시 문자를 소스에 직접 넣으면 에디터가 공백으로 정규화하므로 이스케이프로 고정한다.
        assertThat(LogSanitizer.sanitize("a\u0085b\u2028c\u2029d")).isEqualTo("a_b_c_d");
    }

    @Test
    @DisplayName("null 은 null 그대로 둔다(로그에 \"null\" 로 찍히게)")
    void null은_그대로() {
        assertThat(LogSanitizer.sanitize(null)).isNull();
    }

    @Test
    @DisplayName("정상 문자열은 바꾸지 않는다")
    void 정상문자열은_보존한다() {
        assertThat(LogSanitizer.sanitize("사칭 신고 접수 — 실물 미확인 (2026-08-20)"))
                .isEqualTo("사칭 신고 접수 — 실물 미확인 (2026-08-20)");
    }
}
