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

    @Builder
    private Facility(Long ownerId, String name, String type, String address,
                      BigDecimal latitude, BigDecimal longitude, Integer builtYear,
                      String scale, Integer inspectionCycleMonths, LocalDate nextInspectionDueAt) {
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
    }

    /**
     * 시설물 정보 수정(상태 전이 메서드) — PUT 전체 수정.
     */
    public void updateInfo(String name, String type, String address,
                            BigDecimal latitude, BigDecimal longitude, Integer builtYear,
                            String scale, Integer inspectionCycleMonths, LocalDate nextInspectionDueAt) {
        this.name = name;
        this.type = type;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.builtYear = builtYear;
        this.scale = scale;
        this.inspectionCycleMonths = inspectionCycleMonths;
        this.nextInspectionDueAt = nextInspectionDueAt;
    }

    public boolean isOwnedBy(Long userId) {
        return this.ownerId.equals(userId);
    }
}
