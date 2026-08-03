package com.hajacheck.global.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hajacheck.global.exception.DomainValidationException;
import java.util.Map;
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
    void mergeTextFields_기존키를_보존하고_같은키만_덮어쓴다() {
        String merged = JsonValidator.mergeTextFields(
                "{\"source\":\"MANUAL_INPUT\",\"ntsOutcome\":\"SKIPPED\"}",
                Map.of("ntsOutcome", "VERIFIED", "ntsCheckedAt", "2026-07-31T00:00:00Z"));

        assertThat(JsonValidator.readTextField(merged, "source")).contains("MANUAL_INPUT");
        assertThat(JsonValidator.readTextField(merged, "ntsOutcome")).contains("VERIFIED");
        assertThat(JsonValidator.readTextField(merged, "ntsCheckedAt")).contains("2026-07-31T00:00:00Z");
    }

    @Test
    void mergeTextFields_원본이_null이나_공백이면_새객체로시작한다() {
        assertThat(JsonValidator.readTextField(
                JsonValidator.mergeTextFields(null, Map.of("ntsOutcome", "VERIFIED")),
                "ntsOutcome")).contains("VERIFIED");
        assertThat(JsonValidator.readTextField(
                JsonValidator.mergeTextFields("   ", Map.of("ntsOutcome", "VERIFIED")),
                "ntsOutcome")).contains("VERIFIED");
    }

    @Test
    void mergeTextFields_파손되거나_객체가아닌원본은_지우지않고_보존한다() {
        // 감사용 컬럼이라 "읽을 수 없다"고 버리면 그것도 기록 소실이다 — rawBeforeMerge 로 남긴다.
        String merged = JsonValidator.mergeTextFields("{not-json", Map.of("ntsOutcome", "VERIFIED"));

        assertThat(JsonValidator.readTextField(merged, "ntsOutcome")).contains("VERIFIED");
        assertThat(JsonValidator.readTextField(merged, "rawBeforeMerge")).contains("{not-json");

        String fromArray = JsonValidator.mergeTextFields("[\"VERIFIED\"]", Map.of("ntsOutcome", "VERIFIED"));
        assertThat(JsonValidator.readTextField(fromArray, "rawBeforeMerge")).contains("[\"VERIFIED\"]");
    }

    @Test
    void mergeTextFields_결과는_항상_유효한JSON이다() {
        // jsonb 컬럼에 그대로 적재되므로 문법이 깨지면 flush 시점 원시 SQL 예외로 샌다.
        assertThatCode(() -> JsonValidator.requireValidJson(
                JsonValidator.mergeTextFields("{\"source\":\"MANUAL_INPUT\"}",
                        Map.of("ntsOutcome", "VERIFIED")),
                "병합 결과")).doesNotThrowAnyException();

        // 값에 따옴표·중괄호가 섞여도 Jackson 이 이스케이프한다(문자열 접합 금지 규약).
        String merged = JsonValidator.mergeTextFields(null, Map.of("ntsOutcome", "A\"B{}\\C"));
        assertThatCode(() -> JsonValidator.requireValidJson(merged, "병합 결과")).doesNotThrowAnyException();
        assertThat(JsonValidator.readTextField(merged, "ntsOutcome")).contains("A\"B{}\\C");
    }

    @Test
    void readTextField_깨진JSON은_원문을_노출하지_않는다() {
        // requireValidJson 과 같은 규칙 — 이 메서드는 예외 자체를 던지지 않으므로 메시지 유출 경로가 없다.
        String sensitiveInput = "{\"businessNumber\":\"secret-registration-number\"";

        assertThat(JsonValidator.readTextField(sensitiveInput, "businessNumber")).isEmpty();
    }

    @Test
    void isEmptyJson_null이거나_공백이면_true다() {
        assertThat(JsonValidator.isEmptyJson(null)).isTrue();
        assertThat(JsonValidator.isEmptyJson("")).isTrue();
        assertThat(JsonValidator.isEmptyJson("   ")).isTrue();
    }

    @Test
    void isEmptyJson_빈배열_빈객체_JSON_null은_모두_true다() {
        assertThat(JsonValidator.isEmptyJson("[]")).isTrue();
        assertThat(JsonValidator.isEmptyJson("{}")).isTrue();
        assertThat(JsonValidator.isEmptyJson("null")).isTrue();
    }

    @Test
    void isEmptyJson_값이있는_배열이나_객체는_false다() {
        assertThat(JsonValidator.isEmptyJson("[\"VERIFIED\"]")).isFalse();
        assertThat(JsonValidator.isEmptyJson("{\"source\":\"MANUAL_INPUT\"}")).isFalse();
    }

    @Test
    void isEmptyJson_문법이_깨졌으면_내용없음으로_단정하지않고_false다() {
        // "빈 값"과 "파손"은 다른 상태다 — 깨진 입력을 빈 값으로 오분류하면 실제로 있는 내용을
        // 조용히 무시하게 된다. isEmptyJson은 실제로 비어있음이 확인된 경우에만 true를 반환한다.
        assertThat(JsonValidator.isEmptyJson("{not-json")).isFalse();
    }

    @Test
    void isEmptyJson_스칼라값은_false다() {
        assertThat(JsonValidator.isEmptyJson("\"VERIFIED\"")).isFalse();
        assertThat(JsonValidator.isEmptyJson("1")).isFalse();
        assertThat(JsonValidator.isEmptyJson("true")).isFalse();
    }
}
