package com.hajacheck.core.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hajacheck.core.ai.dto.ReportResponse;
import com.hajacheck.core.report.entity.GroundingCheckResult;
import com.hajacheck.core.report.entity.GroundingCheckTarget;
import com.hajacheck.global.exception.DomainValidationException;
import org.junit.jupiter.api.Test;

class GroundingCheckResultFactoryTest {

    @Test
    void fromAiReport_AI응답을도메인결과로변환() {
        ReportResponse response = new ReportResponse(null, null, null, null, true);
        GroundingCheckTarget target = GroundingCheckTarget.capture(10L, 1, "{}");

        GroundingCheckResult result = GroundingCheckResultFactory.fromAiReport(target, response, "[]");

        assertThat(result.passed()).isTrue();
        assertThat(result.warnings()).isEqualTo("[]");
        assertThat(result.target()).isEqualTo(target);
    }

    @Test
    void fromAiReport_null응답을거부() {
        GroundingCheckTarget target = GroundingCheckTarget.capture(10L, 1, "{}");

        assertThatThrownBy(() -> GroundingCheckResultFactory.fromAiReport(target, null, null))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void fromAiReport_null대상을거부() {
        ReportResponse response = new ReportResponse(null, null, null, null, true);

        assertThatThrownBy(() -> GroundingCheckResultFactory.fromAiReport(null, response, null))
                .isInstanceOf(DomainValidationException.class);
    }
}
