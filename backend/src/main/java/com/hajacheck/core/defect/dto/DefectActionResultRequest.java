package com.hajacheck.core.defect.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * 조치 결과 등록 요청(HAJA-393/#725, "조치 완료 등록" 버튼) — 조치 후 사진/조치 내용/조치일/담당자
 * 4개 필드는 Figma 실사(node 1562-3682)로 확정된 필수 항목이다. status는 별도로 받지 않는다 —
 * {@link com.hajacheck.core.defect.entity.Defect#registerActionResult}가 항상 RESOLVED로만
 * 전이하며, changeStatus()의 정방향 전이 규칙(IN_PROGRESS 상태에서만 사유 없이 허용)을 그대로 따른다.
 */
public record DefectActionResultRequest(
        @NotNull Long actionMediaId,
        @NotBlank @Size(max = 2000) String actionContent,
        @NotNull LocalDate actionDate,
        @NotNull Long actionAssigneeId
) {
}
