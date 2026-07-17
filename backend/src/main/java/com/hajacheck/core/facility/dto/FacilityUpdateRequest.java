package com.hajacheck.core.facility.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 시설물 수정 요청(PUT — 전체 교체). name/type 은 DDL NOT NULL 이라 필수 유지.
 */
public record FacilityUpdateRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 20) String type,
        @Size(max = 300) String address,
        @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal latitude,
        @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal longitude,
        Integer builtYear,
        @Size(max = 100) String scale,
        @Min(0) Integer inspectionCycleMonths,
        LocalDate nextInspectionDueAt
) {
}
