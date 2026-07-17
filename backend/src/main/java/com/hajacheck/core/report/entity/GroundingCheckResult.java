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

    static GroundingCheckResult passed(
            GroundingCheckTarget target, String groundingWarnings) {
        String normalizedWarnings = JsonValidator.normalizeOrRequireValid(
                groundingWarnings, "근거 검증 경고(groundingWarnings)");
        if (!JsonValidator.isEmptyJson(normalizedWarnings)) {
            throw new DomainValidationException(
                    "grounding 통과 결과와 비어있지 않은 grounding 경고는 동시에 있을 수 없다");
        }
        return new GroundingCheckResult(target, true, normalizedWarnings);
    }

    static GroundingCheckResult failed(
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

    /** AI 응답의 상관관계 값이 캡처된 대상과 모두 일치할 때만 결과를 생성한다. */
    public static GroundingCheckResult fromVerifiedAiResponse(
            GroundingCheckTarget target,
            String groundingRequestId,
            Long inspectionId,
            Integer reportVersion,
            String contentHash,
            boolean passed,
            String groundingWarnings) {
        if (target == null) {
            throw new DomainValidationException("grounding 대상은 필수다");
        }
        if (!target.groundingRequestId().equals(groundingRequestId)
                || !target.inspectionId().equals(inspectionId)
                || reportVersion == null
                || target.reportVersion() != reportVersion
                || !target.contentHash().equals(contentHash)) {
            throw new DomainValidationException(
                    "AI grounding 응답이 요청 ID, 보고서 버전 또는 콘텐츠와 일치하지 않는다");
        }
        return passed
                ? passed(target, groundingWarnings)
                : failed(target, groundingWarnings);
    }

    public boolean matches(Long inspectionId, int reportVersion, String contentJson) {
        return target.matches(inspectionId, reportVersion, contentJson);
    }
}
