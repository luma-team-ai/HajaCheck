package com.hajacheck.core.facility.entity;

import com.hajacheck.global.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자·시설별 점검 예정/기한 경과 알림 설정 — DDL inspection_notification_settings 테이블 대응
 * (GitHub #540 ③, V7__inspection_admin_schema.sql). SpringBoot_코드_컨벤션.md §6/§7: @Setter 금지,
 * 상태 변경은 의도가 드러나는 메서드({@link #update})로만 허용한다.
 *
 * <p>notifyBeforeEnabled/notifyBeforeDays/warnOnOverdueEnabled 는 DB 컬럼 기본값(true/7/true, HAJA-498/V21)과
 * 정확히 동일한 애플리케이션 기본값을 {@code InspectionNotificationSettingResponse.defaults()}가
 * 별도로 가진다 — 이 엔티티 행 자체가 없는(사용자가 한 번도 설정하지 않은) 시설물은 그 기본값으로
 * 취급한다({@link com.hajacheck.core.facility.scheduler.InspectionDueNotificationScheduler} 참고).
 */
@Entity
@Getter
@Table(
        name = "inspection_notification_settings",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_inspection_notification_settings_user_facility",
                columnNames = {"user_id", "facility_id"}),
        indexes = @Index(name = "idx_inspection_notification_settings_facility", columnList = "facility_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InspectionNotificationSetting extends BaseTimeEntity {

    // id: PG generated always as identity → IDENTITY 전략
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "facility_id", nullable = false)
    private Long facilityId;

    @Column(name = "notify_before_enabled", nullable = false)
    private boolean notifyBeforeEnabled;

    // DB 타입이 smallint(1~365 체크 제약)라 Short로 매핑해야 한다 — Integer로 매핑하면
    // ddl-auto=validate 스키마 검증이 "int2 vs integer 타입 불일치"로 애플리케이션 기동 자체를 막는다.
    @Column(name = "notify_before_days", nullable = false)
    private Short notifyBeforeDays;

    @Column(name = "warn_on_overdue_enabled", nullable = false)
    private boolean warnOnOverdueEnabled;

    @Builder
    private InspectionNotificationSetting(Long userId, Long facilityId, boolean notifyBeforeEnabled,
                                           Short notifyBeforeDays, boolean warnOnOverdueEnabled) {
        this.userId = userId;
        this.facilityId = facilityId;
        this.notifyBeforeEnabled = notifyBeforeEnabled;
        this.notifyBeforeDays = notifyBeforeDays;
        this.warnOnOverdueEnabled = warnOnOverdueEnabled;
    }

    /** 설정값 갱신(PUT upsert) — 상태 전이 메서드로 캡슐화(Entity §6 컨벤션, Setter 금지). */
    public void update(boolean notifyBeforeEnabled, Short notifyBeforeDays, boolean warnOnOverdueEnabled) {
        this.notifyBeforeEnabled = notifyBeforeEnabled;
        this.notifyBeforeDays = notifyBeforeDays;
        this.warnOnOverdueEnabled = warnOnOverdueEnabled;
    }
}