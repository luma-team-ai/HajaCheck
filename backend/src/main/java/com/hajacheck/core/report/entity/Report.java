package com.hajacheck.core.report.entity;

import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.global.common.BaseTimeEntity;
import com.hajacheck.global.exception.DomainStateTransitionException;
import com.hajacheck.global.exception.DomainValidationException;
import com.hajacheck.global.util.JsonValidator;
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
        if (version < 1) {
            throw new DomainValidationException("보고서 버전은 1 이상이어야 한다");
        }
        requireContent(contentJson);
        return Report.builder()
                .inspectionId(inspectionId)
                .version(version)
                .contentJson(contentJson)
                .status(ReportStatus.DRAFT)
                .createdBy(createdBy)
                .build();
    }

    /** 콘텐츠를 수정하면 이전 콘텐츠에 대한 grounding 판정은 더 이상 유효하지 않다. */
    public void updateContent(String contentJson, Long editedBy) {
        requireDraft("updateContent");
        requireContent(contentJson);
        this.contentJson = contentJson;
        this.groundingCheckPassed = null;
        this.groundingWarnings = null;
        this.editedBy = editedBy;
    }

    /** 비동기 Grounding 요청 전에 현재 보고서 대상을 불변 스냅샷으로 캡처한다. */
    public GroundingCheckTarget captureGroundingTarget() {
        requireDraft("captureGroundingTarget");
        return GroundingCheckTarget.capture(this.inspectionId, this.version, this.contentJson);
    }

    /** 내부 AI 서버에서 유래한 grounding 결과만 별도 단계로 기록한다. */
    public void recordGroundingResult(GroundingCheckResult result, Long editedBy) {
        requireDraft("recordGroundingResult");
        if (result == null) {
            throw new DomainValidationException("grounding 결과는 필수다");
        }
        if (!result.matches(this.inspectionId, this.version, this.contentJson)) {
            throw new DomainValidationException(
                    "grounding 결과가 현재 보고서 버전 또는 콘텐츠와 일치하지 않는다");
        }
        this.groundingCheckPassed = result.passed();
        this.groundingWarnings = result.warnings();
        this.editedBy = editedBy;
    }

    public void finalizeReport(String pdfUrl, Long editedBy) {
        requireDraft("finalizeReport");
        if (!Boolean.TRUE.equals(this.groundingCheckPassed)) {
            throw new DomainStateTransitionException(
                    "finalizeReport 불가: 근거 검증을 통과한 보고서만 확정할 수 있다");
        }
        requirePdfUrl(pdfUrl);
        this.pdfUrl = pdfUrl;
        this.editedBy = editedBy;
        this.status = ReportStatus.FINALIZED;
    }

    private void requireDraft(String action) {
        if (this.status != ReportStatus.DRAFT) {
            throw new DomainStateTransitionException(
                    "%s 불가: 이미 확정된 보고서는 수정할 수 없다".formatted(action));
        }
    }

    private static void requireContent(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            throw new DomainValidationException("보고서 본문 JSON은 필수다");
        }
        JsonValidator.requireValidJson(contentJson, "보고서 본문(contentJson)");
    }

    private static void requirePdfUrl(String pdfUrl) {
        if (pdfUrl == null || pdfUrl.isBlank()) {
            throw new DomainValidationException("확정 보고서 PDF URL은 필수다");
        }
    }
}
