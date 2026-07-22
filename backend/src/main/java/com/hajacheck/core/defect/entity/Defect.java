package com.hajacheck.core.defect.entity;

import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.global.exception.DomainStateTransitionException;
import com.hajacheck.global.exception.DomainValidationException;
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
import jakarta.persistence.Version;
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

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

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
        if (grade == null) {
            throw new DomainValidationException("review 불가: 결함 등급은 필수다");
        }
        if (this.status == DefectStatus.RESOLVED) {
            throw new DomainStateTransitionException(
                    "review 불가: 이미 RESOLVED 상태인 결함은 등급을 변경할 수 없다");
        }
        this.grade = grade;
        this.reviewed = true;
    }

    public void changeStatus(DefectStatus status) {
        changeStatus(status, null);
    }

    /**
     * 상태 전이(HAJA-26 2차: 역행/건너뛰기 허용). 정방향 한 단계 전이는 사유 없이 허용하고,
     * 그 외(역행·건너뛰기) 전이는 {@code reason}이 있어야만 허용한다(PRD FR-4 "역행·건너뛰기는
     * 사유 기록 필수"). 단, 조치완료(RESOLVED)는 사유 유무와 무관하게 이탈(다른 상태로 재전이)이
     * 불가한 종료 상태로 유지한다 — 완료 처리 자체를 되돌리는 것은 별도 스코프.
     */
    public void changeStatus(DefectStatus status, String reason) {
        if (status == null) {
            throw new DomainValidationException("changeStatus 불가: 변경할 상태는 필수다");
        }
        requireNotDeleted("changeStatus");

        if (this.status == DefectStatus.RESOLVED) {
            throw new DomainStateTransitionException(
                    "changeStatus 불가: 조치완료(RESOLVED)는 종료 상태라 다른 상태로 전이할 수 없다");
        }
        if (status == this.status) {
            throw new DomainStateTransitionException(
                    "changeStatus 불가: 현재 상태와 동일한 상태로는 전이할 수 없다 (상태=%s)".formatted(status));
        }

        DefectStatus expectedNext = switch (this.status) {
            case DETECTED -> DefectStatus.CONFIRMED;
            case CONFIRMED -> DefectStatus.ACTION_PENDING;
            case ACTION_PENDING -> DefectStatus.IN_PROGRESS;
            case IN_PROGRESS -> DefectStatus.RESOLVED;
            case RESOLVED -> null;
        };

        boolean isForwardStep = status == expectedNext;
        if (!isForwardStep && (reason == null || reason.isBlank())) {
            throw new DomainValidationException(
                    "changeStatus 불가: 역행/건너뛰기 전이는 사유가 필요하다 (현재 상태=%s, 요청 상태=%s)"
                            .formatted(this.status, status));
        }
        this.status = status;
    }

    public void updateCrackMeasurement(Double crackWidthMm, Double crackLengthMm) {
        requireNotDeleted("updateCrackMeasurement");
        if (this.status == DefectStatus.RESOLVED) {
            throw new DomainStateTransitionException(
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
            throw new DomainStateTransitionException(
                    "%s 불가: 삭제된 결함은 변경할 수 없다".formatted(action));
        }
    }
}
