package com.hajacheck.core.defect.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
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

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "defect_id", nullable = false)
    private Long defectId;

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
