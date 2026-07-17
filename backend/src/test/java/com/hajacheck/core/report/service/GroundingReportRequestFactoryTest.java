package com.hajacheck.core.report.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.core.ai.dto.ReportRequest;
import com.hajacheck.core.report.entity.GroundingRequestContext;
import java.util.List;
import org.junit.jupiter.api.Test;

class GroundingReportRequestFactoryTest {

    @Test
    void from_캡처대상의요청ID와보고서식별자를AI요청에고정() {
        GroundingRequestContext context = GroundingRequestContext.capture(10L, 3);

        ReportRequest request = GroundingReportRequestFactory.from(
                context,
                new ReportRequest.FacilityInfo("시설", "서울"),
                List.of(new ReportRequest.ConfirmedDefect("균열", "1층", "B", "설명")),
                "regenerate");

        assertThat(request.groundingRequestId()).isEqualTo(context.groundingRequestId());
        assertThat(request.inspectionId()).isEqualTo(10L);
        assertThat(request.reportVersion()).isEqualTo(3);
    }
}
