package com.hajacheck.core.inspection.entity;

import com.hajacheck.core.facility.entity.Facility;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 시설별 점검 회차와 진행 상태 — DDL inspections 테이블 대응.
 * SpringBoot_코드_컨벤션.md §6/§7: @Setter 금지. {@code facilityId} 는 FK 값 컬럼을 실제 매핑 소스로 두고,
 * 지연 로딩 연관관계({@code facility})는 조회 전용({@code insertable/updatable = false})으로 병행 제공한다.
 * 도메인 경계를 넘는 {@code createdBy}/{@code assignedInspectorId}(auth 도메인 User 참조)는 Long 값만 보유한다.
 *
 * <p>⚠️ BaseTimeEntity 상속 금지: inspections 테이블에는 updated_at 컬럼이 없다(created_at 만 존재).
 * type/status 는 PG named enum(inspection_type/inspection_status_type) — @JdbcTypeCode(NAMED_ENUM) 매핑.
 *
 * <p>assignedInspectorId(점검 담당자)는 DB 상 not null이며 애플리케이션에서
 * users.status=ACTIVE AND role IN (INSPECTOR, ADMIN) 검증을 거친 값만 들어온다
 * (docs/design/db/table_design.md §inspections — createdBy를 근거 없이 자동 복사하지 않음).
 */
@Entity
@Getter
@Table(
        name = "inspections",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_inspections_facility_round",
                columnNames = {"facility_id", "round_no"}),
        indexes = {
                @Index(name = "idx_inspections_facility", columnList = "facility_id"),
                @Index(name = "idx_inspections_assigned_inspector", columnList = "assigned_inspector_id")
        })
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Inspection {

    // id: PG generated always as identity → IDENTITY 전략
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "facility_id", nullable = false)
    private Long facilityId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "facility_id", insertable = false, updatable = false)
    private Facility facility;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "assigned_inspector_id", nullable = false)
    private Long assignedInspectorId;

    @Column(name = "round_no", nullable = false)
    private Integer roundNo;

    @Column(name = "inspection_date", nullable = false)
    private LocalDate inspectionDate;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "inspection_type", nullable = false)
    private InspectionType type;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "inspection_status_type", nullable = false)
    private InspectionStatus status;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private Inspection(Long facilityId, Long createdBy, Long assignedInspectorId, Integer roundNo,
                        LocalDate inspectionDate, InspectionType type, InspectionStatus status) {
        this.facilityId = facilityId;
        this.createdBy = createdBy;
        this.assignedInspectorId = assignedInspectorId;
        this.roundNo = roundNo;
        this.inspectionDate = inspectionDate;
        this.type = type == null ? InspectionType.REGULAR : type;
        this.status = status == null ? InspectionStatus.CREATED : status;
    }

    public void advanceTo(InspectionStatus next) {
        this.status = next;
    }
}
