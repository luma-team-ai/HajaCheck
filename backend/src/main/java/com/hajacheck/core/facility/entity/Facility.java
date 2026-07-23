package com.hajacheck.core.facility.entity;

import com.hajacheck.global.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 사용자가 소유·관리하는 점검 대상 시설 — DDL facilities 테이블 대응.
 * SpringBoot_코드_컨벤션.md §6/§7: @Setter 금지, 상태 변경은 의도가 드러나는 메서드로.
 *
 * ⚠️ Soft Delete 미적용: DDL facilities 테이블에 is_deleted 컬럼이 없다(§5.3, docs/design/db/table_design.md).
 *    prod ddl-auto=validate 라 DDL에 없는 컬럼을 엔티티에 추가할 수 없으므로 하드 삭제로 구현한다.
 */
@Entity
@Getter
@Table(name = "facilities")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Facility extends BaseTimeEntity {

    // id: PG generated always as identity → IDENTITY 전략
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 20)
    private String type;

    @Column(length = 300)
    private String address;

    @Column(precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(name = "built_year")
    private Integer builtYear;

    @Column(length = 100)
    private String scale;

    @Column(name = "inspection_cycle_months")
    private Integer inspectionCycleMonths;

    @Column(name = "next_inspection_due_at")
    private LocalDate nextInspectionDueAt;

    // 시설물 등록 필드 확장(#628 / HAJA-347) — 대표 사진은 별도 테이블(facility_photos)이라 여기 없다.
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "initial_grade", columnDefinition = "facility_initial_grade_type")
    private FacilityInitialGrade initialGrade;

    @Column(name = "assignee_user_id")
    private Long assigneeUserId;

    @Column(columnDefinition = "text")
    private String memo;

    @Builder
    private Facility(Long ownerId, String name, String type, String address,
                      BigDecimal latitude, BigDecimal longitude, Integer builtYear,
                      String scale, Integer inspectionCycleMonths, LocalDate nextInspectionDueAt,
                      FacilityInitialGrade initialGrade, Long assigneeUserId, String memo) {
        this.ownerId = ownerId;
        this.name = name;
        this.type = type;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.builtYear = builtYear;
        this.scale = scale;
        this.inspectionCycleMonths = inspectionCycleMonths;
        this.nextInspectionDueAt = nextInspectionDueAt;
        this.initialGrade = initialGrade;
        this.assigneeUserId = assigneeUserId;
        this.memo = memo;
    }

    /**
     * 시설물 정보 수정(상태 전이 메서드) — PUT 전체 수정.
     */
    public void updateInfo(String name, String type, String address,
                            BigDecimal latitude, BigDecimal longitude, Integer builtYear,
                            String scale, Integer inspectionCycleMonths, LocalDate nextInspectionDueAt,
                            FacilityInitialGrade initialGrade, Long assigneeUserId, String memo) {
        this.name = name;
        this.type = type;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.builtYear = builtYear;
        this.scale = scale;
        this.inspectionCycleMonths = inspectionCycleMonths;
        this.nextInspectionDueAt = nextInspectionDueAt;
        this.initialGrade = initialGrade;
        this.assigneeUserId = assigneeUserId;
        this.memo = memo;
    }

    /**
     * 점검 주기 설정(dev-04-03, #268) — nextInspectionDueAt = baseDate + inspectionCycleMonths(개월).
     * 1차 산출 기준은 "설정일(오늘)" — 최종 점검일 기준 정교화는 dev-03-02 후속 작업에서 처리한다.
     * baseDate 를 파라미터로 받아 호출부(Service)가 LocalDate.now() 를 주입하게 하고,
     * 테스트에서는 임의 시점을 직접 주입해 결정적으로 검증할 수 있게 한다.
     */
    public void updateSchedule(int inspectionCycleMonths, LocalDate baseDate) {
        this.inspectionCycleMonths = inspectionCycleMonths;
        this.nextInspectionDueAt = baseDate.plusMonths(inspectionCycleMonths);
    }

    public boolean isOwnedBy(Long userId) {
        return this.ownerId.equals(userId);
    }
}
