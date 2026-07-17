package com.hajacheck.core.report.service;

import com.hajacheck.core.ai.dto.ReportResponse;
import com.hajacheck.core.report.entity.GroundingCheckResult;
import com.hajacheck.core.report.entity.GroundingCheckTarget;
import com.hajacheck.global.exception.DomainValidationException;

/** 내부 AI 응답 DTO를 보고서 도메인 값으로 변환하는 서비스 계층 경계. */
public final class GroundingCheckResultFactory {

    private GroundingCheckResultFactory() {
    }

    public static GroundingCheckResult fromAiReport(
            GroundingCheckTarget target, ReportResponse aiReport, String groundingWarnings) {
        if (target == null) {
            throw new DomainValidationException("grounding 대상은 필수다");
        }
        if (aiReport == null) {
            throw new DomainValidationException("AI 보고서 grounding 결과는 필수다");
        }
        return aiReport.groundingOk()
                ? GroundingCheckResult.passed(target, groundingWarnings)
                : GroundingCheckResult.failed(target, groundingWarnings);
    }
}
