package com.hajacheck.core.facility.dto;

import com.hajacheck.core.facility.entity.InspectionNotificationSetting;

/**
 * 점검 알림 설정 조회/저장 응답(#540 ③). 사용자가 한 번도 저장한 적 없는 시설물은 엔티티 행 자체가
 * 없으므로, 그 경우 {@link #defaults()}가 DB 컬럼 기본값(notify_before_enabled=true,
 * notify_before_days=7, warn_on_overdue_enabled=false)과 동일한 값을 반환한다 — 프론트가 "설정 없음"과
 * "기본값으로 설정됨"을 구분할 필요 없이 항상 유효한 설정값을 받도록 한다.
 */
public record InspectionNotificationSettingResponse(
        boolean notifyBeforeEnabled,
        int notifyBeforeDays,
        boolean warnOnOverdueEnabled
) {
    private static final boolean DEFAULT_NOTIFY_BEFORE_ENABLED = true;
    private static final int DEFAULT_NOTIFY_BEFORE_DAYS = 7;
    private static final boolean DEFAULT_WARN_ON_OVERDUE_ENABLED = false;

    public static InspectionNotificationSettingResponse from(InspectionNotificationSetting setting) {
        return new InspectionNotificationSettingResponse(
                setting.isNotifyBeforeEnabled(),
                setting.getNotifyBeforeDays().intValue(),
                setting.isWarnOnOverdueEnabled());
    }

    public static InspectionNotificationSettingResponse defaults() {
        return new InspectionNotificationSettingResponse(
                DEFAULT_NOTIFY_BEFORE_ENABLED, DEFAULT_NOTIFY_BEFORE_DAYS, DEFAULT_WARN_ON_OVERDUE_ENABLED);
    }
}