package com.hajacheck.core.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hajacheck.core.ai.dto.ReportResponse;
import com.hajacheck.core.report.entity.GroundingCheckResult;
import com.hajacheck.global.exception.DomainValidationException;
import org.junit.jupiter.api.Test;

class GroundingCheckResultFactoryTest {

    @Test
    void fromAiReport_AI응답을도메인결과로변환() {
        ReportResponse response = new ReportResponse(null, null, null, null, true);

        GroundingCheckResult result = GroundingCheckResultFactory.fromAiReport(response, "[]");

        assertThat(result.passed()).isTrue();
        assertThat(result.warnings()).isEqualTo("[]");
    }

    @Test
    void fromAiReport_null응답을거부() {
        assertThatThrownBy(() -> GroundingCheckResultFactory.fromAiReport(null, null))
                .isInstanceOf(DomainValidationException.class);
    }
}
