package com.hajacheck.core.inspection.dto;

import com.hajacheck.core.inspection.entity.InspectionType;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * 점검 회차 생성 요청 — 시설물 선택 + 점검일 + 담당자 + 점검 유형.
 * assignedInspectorId는 createdBy(생성자)와 별개로 명시 지정한다 — 근거 없이 자동 복사 금지
 * (docs/design/db/table_design.md §inspections). AuthService.validateAssignableInspector()로 검증.
 *
 * <p>type은 생략 가능 — Inspection.builder()가 null을 REGULAR로 기본 처리한다(엔티티 생성자 참고).
 * 3-인자 생성자는 type 필드 추가 이전 호출부(테스트 등)과의 하위 호환을 위해 유지한다.
 */
public record InspectionCreateRequest(
        @NotNull Long facilityId,
        @NotNull LocalDate inspectionDate,
        @NotNull Long assignedInspectorId,
        InspectionType type
) {
    public InspectionCreateRequest(Long facilityId, LocalDate inspectionDate, Long assignedInspectorId) {
        this(facilityId, inspectionDate, assignedInspectorId, null);
    }
}
