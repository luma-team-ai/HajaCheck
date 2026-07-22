package com.hajacheck.core.defect.dto;

import com.hajacheck.core.defect.entity.DefectGrade;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Getter;

/**
 * PATCH /api/defects/{id} 요청 DTO.
 * grade·isDeleted 중 정확히 하나만 지정 — 유효성 검증은 서비스 레벨에서 처리.
 */
@Getter
@Builder
public class DefectRevisionRequest {
    private DefectGrade grade;

    @JsonProperty("isDeleted")
    private Boolean deleted;

    @NotBlank(message = "reason은 필수입니다")
    @Size(min = 1, max = 500, message = "reason은 1~500자여야 합니다")
    private String reason;
}
