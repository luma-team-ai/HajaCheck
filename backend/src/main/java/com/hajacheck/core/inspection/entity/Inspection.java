package com.hajacheck.core.inspection.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
 * SpringBoot_코드_컨벤션.md §6/§7: @Setter 금지, 연관관계 대신 FK 값 컬럼만 보유(도메인 간 결합 최소화 원칙,
 * Facility/User 참조 패턴과 동일 — facilityId/createdBy 는 Long 컬럼).
 *
 * <p>⚠️ BaseTimeEntity 상속 금지: inspections 테이블에는 updated_at 컬럼이 없다(created_at 만 존재).
 * status 는 PG named enum(inspection_status_type) — @JdbcTypeCode(NAMED_ENUM) 매핑.
 */
@Entity
@Getter
@Table(name = "inspections")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Inspection {

    // id: PG generated always as identity → IDENTITY 전략
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "facility_id", nullable = false)
    private Long facilityId;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "round_no", nullable = false)
    private Integer roundNo;

    @Column(name = "inspection_date", nullable = false)
    private LocalDate inspectionDate;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "inspection_status_type", nullable = false)
    private InspectionStatus status;

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Builder
    private Inspection(Long facilityId, Long createdBy, Integer roundNo,
                        LocalDate inspectionDate, InspectionStatus status) {
        this.facilityId = facilityId;
        this.createdBy = createdBy;
        this.roundNo = roundNo;
        this.inspectionDate = inspectionDate;
        this.status = status == null ? InspectionStatus.CREATED : status;
    }
}
