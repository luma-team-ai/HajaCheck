package com.hajacheck.core.defect.entity;

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
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** 결함 필드 변경을 append-only로 보존하는 감사 이력. */
@Entity
@Getter
@Table(name = "defect_revisions", indexes = {
        @Index(name = "idx_defect_revisions_defect", columnList = "defect_id")
})
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DefectRevision {

    /** 오탐 삭제·복구 이력. new_value='true'=삭제, 'false'=복구(#1399). */
    public static final String FIELD_IS_DELETED = "is_deleted";

    /**
     * 재분석 세대 교체 마커(#1401) — 이 하자가 재분석으로 대체됐음을 표시한다.
     *
     * <p>없으면 되살리기가 세대를 구분하지 못한다: 재분석은 비삭제분만 소프트삭제하므로
     * 검수자가 이미 지운 하자는 is_deleted 이력을 그대로 유지하고, 재분석 게이트가 "비삭제 0건이면
     * 허용"이라 <b>전부 오탐 삭제 → 재분석</b> 시퀀스를 거치면 대체된 구회차 삭제분이 그대로
     * 되살리기 후보로 남는다. 시각·id 비교로는 갈리지 않는다(같은 세대에서 일부만 삭제돼도
     * 오판) — 그래서 교체 시점에 명시적으로 마킹한다.
     */
    public static final String FIELD_REANALYSIS_SUPERSEDED = "reanalysis_superseded";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "defect_id", nullable = false)
    private Long defectId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "defect_id", insertable = false, updatable = false)
    private Defect defect;

    @Column(name = "revised_by", nullable = false)
    private Long revisedBy;

    @Column(name = "field_changed", nullable = false, length = 50)
    private String fieldChanged;

    @Column(name = "old_value", length = 255)
    private String oldValue;

    @Column(name = "new_value", length = 255)
    private String newValue;

    @Column(length = 500)
    private String reason;

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Builder(access = AccessLevel.PRIVATE)
    private DefectRevision(Long defectId, Long revisedBy, String fieldChanged,
                           String oldValue, String newValue, String reason) {
        this.defectId = defectId;
        this.revisedBy = revisedBy;
        this.fieldChanged = fieldChanged;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.reason = reason;
    }

    public static DefectRevision record(Long defectId, Long revisedBy, String fieldChanged,
                                        String oldValue, String newValue, String reason) {
        return DefectRevision.builder()
                .defectId(defectId)
                .revisedBy(revisedBy)
                .fieldChanged(fieldChanged)
                .oldValue(oldValue)
                .newValue(newValue)
                .reason(reason)
                .build();
    }
}
