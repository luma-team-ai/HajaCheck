package com.hajacheck.core.defect.entity;

import com.hajacheck.core.inspection.entity.Inspection;
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
 * SpringBoot_코드_컨벤션.md §6/§7: @Setter 금지. {@code inspectionId} 는 FK 값 컬럼을 실제 매핑 소스로 두고,
 * 지연 로딩 연관관계({@code inspection})는 조회 전용({@code insertable/updatable = false})으로 병행 제공한다.
 *
 * <p>⚠️ BaseTimeEntity 상속 금지: defects 테이블에는 updated_at 컬럼이 없다(created_at 만 존재).
 * type/grade/status 는 PG named enum — @JdbcTypeCode(NAMED_ENUM) 매핑. grade 는 DDL 상 nullable.
 */
@Entity
@Getter
@Table(name = "defects", indexes = {
        @Index(name = "idx_defects_inspection", columnList = "inspection_id")
})
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Defect {

    // id: PG generated always as identity → IDENTITY 전략
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "inspection_id", nullable = false)
    private Long inspectionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inspection_id", insertable = false, updatable = false)
    private Inspection inspection;

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

    public void review(DefectGrade grade) {
        requireNotDeleted("review");
        if (this.status == DefectStatus.RESOLVED) {
            throw new IllegalStateException(
                    "review 불가: 이미 RESOLVED 상태인 결함은 등급을 변경할 수 없다");
        }
        this.grade = grade;
        this.reviewed = true;
    }

    public void changeStatus(DefectStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("changeStatus 불가: 변경할 상태는 필수다");
        }
        requireNotDeleted("changeStatus");

        DefectStatus expectedNext = switch (this.status) {
            case DETECTED -> DefectStatus.CONFIRMED;
            case CONFIRMED -> DefectStatus.ACTION_PENDING;
            case ACTION_PENDING -> DefectStatus.IN_PROGRESS;
            case IN_PROGRESS -> DefectStatus.RESOLVED;
            case RESOLVED -> null;
        };

        if (status != expectedNext) {
            throw new IllegalStateException(
                    "changeStatus 불가: 현재 상태=%s, 허용되는 다음 상태=%s, 요청 상태=%s"
                            .formatted(this.status, expectedNext, status));
        }
        this.status = status;
    }

    public void updateCrackMeasurement(Double crackWidthMm, Double crackLengthMm) {
        requireNotDeleted("updateCrackMeasurement");
        if (this.status == DefectStatus.RESOLVED) {
            throw new IllegalStateException(
                    "updateCrackMeasurement 불가: 이미 RESOLVED 상태인 결함은 측정값을 변경할 수 없다");
        }
        this.crackWidthMm = crackWidthMm;
        this.crackLengthMm = crackLengthMm;
    }

    public void softDelete() {
        if (this.deleted) {
            return;
        }
        this.deleted = true;
    }

    private void requireNotDeleted(String action) {
        if (this.deleted) {
            throw new IllegalStateException(
                    "%s 불가: 삭제된 결함은 변경할 수 없다".formatted(action));
        }
    }
}
