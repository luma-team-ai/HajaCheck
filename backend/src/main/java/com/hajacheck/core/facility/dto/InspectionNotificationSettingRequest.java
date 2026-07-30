package com.hajacheck.core.facility.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 점검 알림 설정 저장(upsert) 요청 — inspection_notification_settings 컬럼과 1:1(#540 ③).
 * notifyBeforeDays 범위는 DB 체크 제약(1~365)과 동일하게 Bean Validation으로도 사전 방어한다.
 */
public record InspectionNotificationSettingRequest(
        @NotNull Boolean notifyBeforeEnabled,
        @NotNull @Min(1) @Max(365) Integer notifyBeforeDays,
        @NotNull Boolean warnOnOverdueEnabled
) {
}