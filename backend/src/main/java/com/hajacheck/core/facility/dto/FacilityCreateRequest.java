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

/**
 * 시설물 등록 요청. name/type 은 DDL NOT NULL, 나머지는 DDL NULL 허용(§5.3)에 맞춰 선택 입력.
 *
 * <p>initialGrade/assigneeUserId/memo 는 #628(HAJA-347) 등록 필드 확장 — 전부 선택 입력이다.
 * assigneeUserId 는 값이 있을 때만 서비스 계층에서 AuthService.validateAssignableInspector로 검증한다
 * (활성 사용자·INSPECTOR/ADMIN 역할·요청자와 동일 회사·양쪽 유효 멤버십, inspections와 동일 패턴).
 * 대표 사진(photoUrls)은 Polalise DDL 검토 후 별도 후속으로 반영 예정(#632) — 이번 범위에서 제외.
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
        // @Min(1) @Max(120): FacilityScheduleRequest(설정 전용)와 동일 범위로 통일(#1518/UT-081).
        // 과거엔 0을 "주기 미설정"으로 허용했으나, 등록 폼(FacilityFormModal)이 이미 유형 선택에서
        // 값을 자동 도출해 null 또는 1~24 중 하나만 보내므로(0을 보내는 UI 경로 자체가 없음, #629/#731)
        // API 직접 호출로만 도달 가능한 0은 막는 편이 두 API의 검증 기준을 일관되게 유지한다.
        @Min(1) @Max(120) Integer inspectionCycleMonths,
        LocalDate nextInspectionDueAt,
        FacilityInitialGrade initialGrade,
        Long assigneeUserId,
        @Size(max = 2000) String memo
) {
}
