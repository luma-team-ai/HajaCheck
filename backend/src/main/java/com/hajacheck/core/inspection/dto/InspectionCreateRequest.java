package com.hajacheck.core.inspection.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * 점검 회차 생성 요청 — 시설물 선택 + 점검일 + 담당자.
 * assignedInspectorId는 createdBy(생성자)와 별개로 명시 지정한다 — 근거 없이 자동 복사 금지
 * (docs/design/db/table_design.md §inspections). AuthService.validateAssignableInspector()로 검증.
 */
public record InspectionCreateRequest(
        @NotNull Long facilityId,
        @NotNull LocalDate inspectionDate,
        @NotNull Long assignedInspectorId
) {
}
