package com.hajacheck.core.analysis.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hajacheck.global.exception.DomainValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * InspectionAnalysisNotificationPayload 순수 단위 테스트(NOTI-01 나머지, #494/#495). 스프링
 * 컨텍스트·mock 불요.
 */
class InspectionAnalysisNotificationPayloadTest {

    @Test
    @DisplayName("serialize는 inspectionId와 '{roundNo}회차' description을 담는다")
    void serialize_필드포함() {
        String json = InspectionAnalysisNotificationPayload.serialize(100L, 3);

        assertThat(json)
                .contains("\"inspectionId\":100")
                .contains("\"description\":\"3회차\"");
    }

    @Test
    @DisplayName("roundNo가 null이면 description 없이 inspectionId만 담는다")
    void serialize_roundNoNull이면_description은null() {
        String json = InspectionAnalysisNotificationPayload.serialize(100L, null);

        assertThat(json)
                .contains("\"inspectionId\":100")
                .contains("\"description\":null");
    }

    @Test
    @DisplayName("inspectionId가 null이면 DomainValidationException")
    void serialize_inspectionId없으면_예외() {
        assertThatThrownBy(() -> InspectionAnalysisNotificationPayload.serialize(null, 3))
                .isInstanceOf(DomainValidationException.class);
    }
}
