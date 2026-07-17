package com.hajacheck.core.report.entity;

import com.hajacheck.global.exception.DomainValidationException;
import com.hajacheck.global.util.JsonValidator;

/**
 * 보고서 도메인이 사용하는 grounding 판정값이다. AI 전송 DTO와의 변환은 서비스 계층에서 수행한다.
 */
public final class GroundingCheckResult {

    private final GroundingCheckTarget target;
    private final boolean passed;
    private final String warnings;

    private GroundingCheckResult(GroundingCheckTarget target, boolean passed, String warnings) {
        if (target == null) {
            throw new DomainValidationException("grounding 대상은 필수다");
        }
        this.target = target;
        this.passed = passed;
        this.warnings = warnings;
    }

    public static GroundingCheckResult passed(
            GroundingCheckTarget target, String groundingWarnings) {
        String normalizedWarnings = JsonValidator.normalizeOrRequireValid(
                groundingWarnings, "근거 검증 경고(groundingWarnings)");
        if (!JsonValidator.isEmptyJson(normalizedWarnings)) {
            throw new DomainValidationException(
                    "grounding 통과 결과와 비어있지 않은 grounding 경고는 동시에 있을 수 없다");
        }
        return new GroundingCheckResult(target, true, normalizedWarnings);
    }

    public static GroundingCheckResult failed(
            GroundingCheckTarget target, String groundingWarnings) {
        String normalizedWarnings = JsonValidator.normalizeOrRequireValid(
                groundingWarnings, "근거 검증 경고(groundingWarnings)");
        return new GroundingCheckResult(target, false, normalizedWarnings);
    }

    public GroundingCheckTarget target() {
        return target;
    }

    public boolean passed() {
        return passed;
    }

    public String warnings() {
        return warnings;
    }

    public boolean matches(Long inspectionId, int reportVersion, String contentJson) {
        return target.matches(inspectionId, reportVersion, contentJson);
    }
}
