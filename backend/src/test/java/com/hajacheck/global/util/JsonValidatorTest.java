package com.hajacheck.global.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hajacheck.global.exception.DomainValidationException;
import org.junit.jupiter.api.Test;

class JsonValidatorTest {

    @Test
    void invalidJson_doesNotExposeOriginalInputOrParserDetail() {
        String sensitiveInput = "{\"businessNumber\":\"secret-registration-number\"";

        assertThatThrownBy(() -> JsonValidator.requireValidJson(sensitiveInput, "사업자등록 OCR 원본"))
                .isInstanceOf(DomainValidationException.class)
                .hasMessage("사업자등록 OCR 원본: 유효한 JSON이어야 한다")
                .hasMessageNotContaining("secret-registration-number")
                .hasMessageNotContaining("Unexpected end-of-input");
    }

    @Test
    void readTextField_최상위_문자열필드를_읽는다() {
        String json = "{\"source\":\"MANUAL_INPUT\",\"ntsOutcome\":\"VERIFIED\"}";

        assertThat(JsonValidator.readTextField(json, "ntsOutcome")).contains("VERIFIED");
        assertThat(JsonValidator.readTextField(json, "source")).contains("MANUAL_INPUT");
    }

    @Test
    void readTextField_읽을수없는_모든경우는_empty이고_예외를_던지지않는다() {
        // 조회 경로에서 감사용 jsonb 를 들여다보는 용도라, 어떤 입력에도 예외가 나가면 안 된다
        // (예외가 나면 무관한 조회 API 가 통째로 500 이 된다).
        assertThatCode(() -> {
            assertThat(JsonValidator.readTextField(null, "ntsOutcome")).isEmpty();
            assertThat(JsonValidator.readTextField("", "ntsOutcome")).isEmpty();
            assertThat(JsonValidator.readTextField("   ", "ntsOutcome")).isEmpty();
            // 문법 오류
            assertThat(JsonValidator.readTextField("{not-json", "ntsOutcome")).isEmpty();
            // 키 없음
            assertThat(JsonValidator.readTextField("{\"source\":\"X\"}", "ntsOutcome")).isEmpty();
            // JSON null
            assertThat(JsonValidator.readTextField("{\"ntsOutcome\":null}", "ntsOutcome")).isEmpty();
            // 비문자열(객체/배열/숫자/불리언)
            assertThat(JsonValidator.readTextField("{\"ntsOutcome\":{\"a\":\"b\"}}", "ntsOutcome")).isEmpty();
            assertThat(JsonValidator.readTextField("{\"ntsOutcome\":[\"VERIFIED\"]}", "ntsOutcome")).isEmpty();
            assertThat(JsonValidator.readTextField("{\"ntsOutcome\":1}", "ntsOutcome")).isEmpty();
            // 최상위가 객체가 아님
            assertThat(JsonValidator.readTextField("[\"VERIFIED\"]", "ntsOutcome")).isEmpty();
            assertThat(JsonValidator.readTextField("\"VERIFIED\"", "ntsOutcome")).isEmpty();
        }).doesNotThrowAnyException();
    }

    @Test
    void readTextField_깨진JSON은_원문을_노출하지_않는다() {
        // requireValidJson 과 같은 규칙 — 이 메서드는 예외 자체를 던지지 않으므로 메시지 유출 경로가 없다.
        String sensitiveInput = "{\"businessNumber\":\"secret-registration-number\"";

        assertThat(JsonValidator.readTextField(sensitiveInput, "businessNumber")).isEmpty();
    }
}
