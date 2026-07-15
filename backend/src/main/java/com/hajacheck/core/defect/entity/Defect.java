package com.hajacheck.core.defect.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
 * 점검 이미지에서 탐지되거나 검토된 시설 결함 — DDL defects 테이블 대응.
 * SpringBoot_코드_컨벤션.md §6/§7: @Setter 금지, 연관관계 대신 FK 값 컬럼만 보유(inspectionId).
 *
 * <p>⚠️ BaseTimeEntity 상속 금지: defects 테이블에는 updated_at 컬럼이 없다(created_at 만 존재).
 * type/grade/status 는 PG named enum — @JdbcTypeCode(NAMED_ENUM) 매핑. grade 는 DDL 상 nullable.
 */
@Entity
@Getter
@Table(name = "defects")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Defect {

    // id: PG generated always as identity → IDENTITY 전략
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "inspection_id", nullable = false)
    private Long inspectionId;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "defect_type", nullable = false)
    private DefectType type;

    @Column(name = "bbox_x")
    private Double bboxX;

    @Column(name = "bbox_y")
    private Double bboxY;

    @Column(name = "bbox_w")
    private Double bboxW;

    @Column(name = "bbox_h")
    private Double bboxH;

    @Column(nullable = false)
    private Double confidence;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "defect_grade_type")
    private DefectGrade grade;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "defect_status_type", nullable = false)
    private DefectStatus status;

    @Column(name = "is_reviewed", nullable = false)
    private boolean reviewed;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted;

    @Column(name = "crack_width_mm")
    private Double crackWidthMm;

    @Column(name = "crack_length_mm")
    private Double crackLengthMm;

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Builder
    private Defect(Long inspectionId, DefectType type, Double bboxX, Double bboxY, Double bboxW, Double bboxH,
                    Double confidence, DefectGrade grade, DefectStatus status, boolean reviewed, boolean deleted,
                    Double crackWidthMm, Double crackLengthMm) {
        this.inspectionId = inspectionId;
        this.type = type;
        this.bboxX = bboxX;
        this.bboxY = bboxY;
        this.bboxW = bboxW;
        this.bboxH = bboxH;
        this.confidence = confidence;
        this.grade = grade;
        this.status = status == null ? DefectStatus.DETECTED : status;
        this.reviewed = reviewed;
        this.deleted = deleted;
        this.crackWidthMm = crackWidthMm;
        this.crackLengthMm = crackLengthMm;
    }
}
