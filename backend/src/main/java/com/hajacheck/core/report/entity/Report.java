package com.hajacheck.core.report.entity;

import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.global.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 점검 결과를 기반으로 생성한 버전별 보고서. */
@Entity
@Getter
@Table(
        name = "reports",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_reports_inspection_version",
                columnNames = {"inspection_id", "version"}),
        indexes = {
                @Index(name = "idx_reports_created_by", columnList = "created_by"),
                @Index(name = "idx_reports_edited_by", columnList = "edited_by")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Report extends BaseTimeEntity {

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

    @Column(nullable = false)
    private int version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content_json", columnDefinition = "jsonb", nullable = false)
    private String contentJson;

    @Column(name = "grounding_check_passed")
    private Boolean groundingCheckPassed;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "grounding_warnings", columnDefinition = "jsonb")
    private String groundingWarnings;

    @Column(name = "pdf_url", length = 500)
    private String pdfUrl;

    @Column(name = "edited_by")
    private Long editedBy;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "report_status_type", nullable = false)
    private ReportStatus status;

    @Column(name = "created_by")
    private Long createdBy;

    @Builder(access = AccessLevel.PRIVATE)
    private Report(Long inspectionId, int version, String contentJson,
                   Boolean groundingCheckPassed, String groundingWarnings,
                   String pdfUrl, Long editedBy, ReportStatus status, Long createdBy) {
        this.inspectionId = inspectionId;
        this.version = version;
        this.contentJson = contentJson;
        this.groundingCheckPassed = groundingCheckPassed;
        this.groundingWarnings = groundingWarnings;
        this.pdfUrl = pdfUrl;
        this.editedBy = editedBy;
        this.status = status == null ? ReportStatus.DRAFT : status;
        this.createdBy = createdBy;
    }

    public static Report draft(Long inspectionId, int version, String contentJson, Long createdBy) {
        requireContent(contentJson);
        return Report.builder()
                .inspectionId(inspectionId)
                .version(version)
                .contentJson(contentJson)
                .status(ReportStatus.DRAFT)
                .createdBy(createdBy)
                .build();
    }

    public void updateContent(String contentJson, Boolean groundingCheckPassed,
                              String groundingWarnings, Long editedBy) {
        requireDraft("updateContent");
        requireContent(contentJson);
        this.contentJson = contentJson;
        this.groundingCheckPassed = groundingCheckPassed;
        this.groundingWarnings = groundingWarnings;
        this.editedBy = editedBy;
    }

    public void finalizeReport(String pdfUrl, Long editedBy) {
        requireDraft("finalizeReport");
        if (!Boolean.TRUE.equals(this.groundingCheckPassed)) {
            throw new IllegalStateException(
                    "finalizeReport 불가: 근거 검증을 통과한 보고서만 확정할 수 있다");
        }
        this.pdfUrl = pdfUrl;
        this.editedBy = editedBy;
        this.status = ReportStatus.FINALIZED;
    }

    private void requireDraft(String action) {
        if (this.status != ReportStatus.DRAFT) {
            throw new IllegalStateException(
                    "%s 불가: 이미 확정된 보고서는 수정할 수 없다".formatted(action));
        }
    }

    private static void requireContent(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            throw new IllegalArgumentException("보고서 본문 JSON은 필수다");
        }
    }
}
