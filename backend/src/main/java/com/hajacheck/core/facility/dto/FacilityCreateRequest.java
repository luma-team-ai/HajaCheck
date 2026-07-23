package com.hajacheck.core.facility.dto;

import com.hajacheck.core.facility.entity.FacilityInitialGrade;
import com.hajacheck.core.facility.validation.ValidBuiltYear;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 시설물 등록 요청. name/type 은 DDL NOT NULL, 나머지는 DDL NULL 허용(§5.3)에 맞춰 선택 입력.
 *
 * <p>photoUrls/initialGrade/assigneeUserId/memo 는 #628(HAJA-347) 등록 필드 확장 — 전부 선택 입력이다.
 * assigneeUserId 는 값이 있을 때만 서비스 계층에서 AuthService.validateAssignableInspector로 검증한다
 * (활성 사용자·INSPECTOR/ADMIN 역할·요청자와 동일 회사·양쪽 유효 멤버십, inspections와 동일 패턴).
 */
public record FacilityCreateRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 20) String type,
        @Size(max = 300) String address,
        @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal latitude,
        @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal longitude,
        // 1900 ~ 현재연도+1. 상한이 동적이라 @Max 로 표현 불가 → 커스텀 제약(#351).
        // FE(#352)와 동일 범위를 서버에서도 강제한다 — FE 만 고치면 API 직접 호출로 우회 가능.
        @ValidBuiltYear Integer builtYear,
        @Size(max = 100) String scale,
        // @Max(120): 상한(10년) — FacilityScheduleRequest 와 동일 기준(#351).
        // @Min(0) 유지: 여기서는 "주기 미설정"(0)을 허용한다(설정 전용인 Schedule 요청은 @Min(1)).
        @Min(0) @Max(120) Integer inspectionCycleMonths,
        LocalDate nextInspectionDueAt,
        // 대표 사진(최대 4장) — facility_photos.sort_order(0~3) 로 저장되는 순서는 이 리스트의 인덱스다.
        @Size(max = 4) List<@NotBlank @Size(max = 500) String> photoUrls,
        FacilityInitialGrade initialGrade,
        Long assigneeUserId,
        @Size(max = 2000) String memo
) {
}
