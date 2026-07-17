package com.hajacheck.core.report.entity;

import com.hajacheck.core.ai.dto.ReportResponse;
import com.hajacheck.global.exception.DomainValidationException;
import com.hajacheck.global.util.JsonValidator;

/**
 * 백엔드가 내부 AI 서버에서 직접 받은 보고서 응답으로부터 도출한 grounding 결과다.
 * 요청 DTO의 boolean 값을 보고서 엔티티에 직접 연결하지 못하도록 생성 경계를 한곳으로 제한한다.
 */
public final class GroundingCheckResult {

    private final boolean passed;
    private final String warnings;

    private GroundingCheckResult(boolean passed, String warnings) {
        this.passed = passed;
        this.warnings = warnings;
    }

    public static GroundingCheckResult fromAiReport(ReportResponse aiReport, String groundingWarnings) {
        if (aiReport == null) {
            throw new DomainValidationException("AI 보고서 grounding 결과는 필수다");
        }
        String normalizedWarnings = JsonValidator.normalizeOrRequireValid(
                groundingWarnings, "근거 검증 경고(groundingWarnings)");
        if (aiReport.groundingOk() && !JsonValidator.isEmptyJson(normalizedWarnings)) {
            throw new DomainValidationException(
                    "grounding 통과 결과와 비어있지 않은 grounding 경고는 동시에 있을 수 없다");
        }
        return new GroundingCheckResult(aiReport.groundingOk(), normalizedWarnings);
    }

    public boolean passed() {
        return passed;
    }

    public String warnings() {
        return warnings;
    }
}
