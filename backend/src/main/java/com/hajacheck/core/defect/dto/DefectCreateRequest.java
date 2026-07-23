package com.hajacheck.core.defect.dto;

import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectType;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;

/**
 * POST /api/inspections/{id}/defects 요청 DTO.
 * type은 필수, bboxX/Y/W/H는 선택적이나 하나라도 지정 시 모두 필수(서비스 레벨 검증),
 * grade는 선택적(기본값 null로 미검수 상태).
 */
@Getter
@Builder
public class DefectCreateRequest {
    @NotNull(message = "type은 필수입니다")
    private DefectType type;

    private Double bboxX;
    private Double bboxY;
    private Double bboxW;
    private Double bboxH;

    private DefectGrade grade;
}
