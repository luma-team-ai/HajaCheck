package com.hajacheck.core.facility.dto;

import com.hajacheck.core.facility.validation.ValidBuiltYear;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 시설물 수정 요청(PUT — 전체 교체). name/type 은 DDL NOT NULL 이라 필수 유지.
 * 제약은 FacilityCreateRequest 와 동일하게 유지한다(PUT 이 전체 교체라 등록과 같은 규칙이어야 함).
 */
public record FacilityUpdateRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 20) String type,
        @Size(max = 300) String address,
        @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal latitude,
        @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal longitude,
        // 1900 ~ 현재연도+1. 상한이 동적이라 @Max 로 표현 불가 → 커스텀 제약(#351).
        @ValidBuiltYear Integer builtYear,
        @Size(max = 100) String scale,
        // @Max(120): 상한(10년) — FacilityScheduleRequest 와 동일 기준(#351).
        @Min(0) @Max(120) Integer inspectionCycleMonths,
        LocalDate nextInspectionDueAt
) {
}
