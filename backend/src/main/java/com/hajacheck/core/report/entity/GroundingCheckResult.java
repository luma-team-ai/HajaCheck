package com.hajacheck.core.report.entity;

import com.hajacheck.global.exception.DomainValidationException;
import com.hajacheck.global.util.JsonValidator;

/**
 * 보고서 도메인이 사용하는 grounding 판정값이다. AI 전송 DTO와의 변환은 서비스 계층에서 수행한다.
 */
public final class GroundingCheckResult {

    private final boolean passed;
    private final String warnings;

    private GroundingCheckResult(boolean passed, String warnings) {
        this.passed = passed;
        this.warnings = warnings;
    }

    public static GroundingCheckResult passed(String groundingWarnings) {
        String normalizedWarnings = JsonValidator.normalizeOrRequireValid(
                groundingWarnings, "근거 검증 경고(groundingWarnings)");
        if (!JsonValidator.isEmptyJson(normalizedWarnings)) {
            throw new DomainValidationException(
                    "grounding 통과 결과와 비어있지 않은 grounding 경고는 동시에 있을 수 없다");
        }
        return new GroundingCheckResult(true, normalizedWarnings);
    }

    public static GroundingCheckResult failed(String groundingWarnings) {
        String normalizedWarnings = JsonValidator.normalizeOrRequireValid(
                groundingWarnings, "근거 검증 경고(groundingWarnings)");
        return new GroundingCheckResult(false, normalizedWarnings);
    }

    public boolean passed() {
        return passed;
    }

    public String warnings() {
        return warnings;
    }
}
