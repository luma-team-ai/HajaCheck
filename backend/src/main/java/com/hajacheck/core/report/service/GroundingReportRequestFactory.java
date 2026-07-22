package com.hajacheck.core.report.service;

import com.hajacheck.core.ai.dto.ReportRequest;
import com.hajacheck.core.report.entity.GroundingRequestContext;
import com.hajacheck.global.exception.DomainValidationException;
import java.util.List;

/** 캡처된 보고서 target과 AI 보고서 요청을 하나의 공식 상관관계 경로로 묶는다. */
public final class GroundingReportRequestFactory {

    private GroundingReportRequestFactory() {
    }

    public static ReportRequest from(
            GroundingRequestContext context,
            ReportRequest.FacilityInfo facilityInfo,
            List<ReportRequest.ConfirmedDefect> confirmedDefects,
            String onMismatch) {
        if (context == null) {
            throw new DomainValidationException("grounding 요청 context는 필수다");
        }
        return new ReportRequest(
                facilityInfo,
                confirmedDefects,
                onMismatch,
                context.groundingRequestId(),
                context.inspectionId(),
                context.reportVersion());
    }
}
