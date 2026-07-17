package com.hajacheck.global.util;

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
}
