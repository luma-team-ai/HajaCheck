package com.hajacheck.core.report.entity;

import com.hajacheck.global.exception.DomainValidationException;
import java.util.UUID;

/** AI 요청 전에 고정하는 상관관계 식별자. 생성 payload가 아직 없으므로 콘텐츠 해시는 포함하지 않는다. */
public record GroundingRequestContext(String groundingRequestId, Long inspectionId, int reportVersion) {

    public GroundingRequestContext {
        if (groundingRequestId == null || groundingRequestId.isBlank()) {
            throw new DomainValidationException("grounding 요청 ID는 필수다");
        }
        if (inspectionId == null) {
            throw new DomainValidationException("grounding 대상 점검 ID는 필수다");
        }
        if (reportVersion < 1) {
            throw new DomainValidationException("grounding 대상 보고서 버전은 1 이상이어야 한다");
        }
    }

    public static GroundingRequestContext capture(Long inspectionId, int reportVersion) {
        return new GroundingRequestContext(UUID.randomUUID().toString(), inspectionId, reportVersion);
    }
}
