package com.hajacheck.core.defect.dto;

import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;

/**
 * POST /api/inspections/{id}/defects 요청 DTO.
 * type은 필수, bboxX/Y/W/H는 선택적이나 하나라도 지정 시 모두 필수(서비스 레벨 검증),
 * grade는 선택적(기본값 null로 미검수 상태).
 * bboxX/Y/W/H: 0.0~1.0 범위의 정규화된 좌표(nullable — null은 유효, 지정 시에만 범위 검증).
 */
@Getter
@Builder
public class DefectCreateRequest {
    @NotNull(message = "type은 필수입니다")
    private DefectType type;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private Double bboxX;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private Double bboxY;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private Double bboxW;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private Double bboxH;

    private DefectGrade grade;
}
