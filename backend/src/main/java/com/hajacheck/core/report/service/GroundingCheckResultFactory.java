package com.hajacheck.core.report.service;

import com.hajacheck.core.ai.dto.ReportResponse;
import com.hajacheck.core.report.entity.GroundingCheckResult;
import com.hajacheck.core.report.entity.GroundingCheckTarget;
import com.hajacheck.core.report.entity.GroundingRequestContext;
import com.hajacheck.global.exception.DomainValidationException;

/** 내부 AI 응답 DTO를 보고서 도메인 값으로 변환하는 서비스 계층 경계. */
public final class GroundingCheckResultFactory {

    private GroundingCheckResultFactory() {
    }

    public static GroundingCheckResult fromAiReport(
            GroundingRequestContext context,
            ReportResponse aiReport,
            String groundingWarnings) {
        if (context == null) {
            throw new DomainValidationException("grounding 요청 context는 필수다");
        }
        if (aiReport == null) {
            throw new DomainValidationException("AI 보고서 grounding 결과는 필수다");
        }
        String reportContentJson = GroundingReportContentSerializer.serialize(aiReport);
        GroundingCheckTarget target = GroundingCheckTarget.capture(context, reportContentJson);
        return GroundingCheckResult.fromVerifiedAiResponse(
                target,
                aiReport.groundingRequestId(),
                aiReport.inspectionId(),
                aiReport.reportVersion(),
                aiReport.contentHash(),
                aiReport.groundingOk(),
                groundingWarnings);
    }
}
