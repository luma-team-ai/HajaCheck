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
import java.time.LocalDateTime;
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

    /**
     * 보고서 생성 시점의 점검 회차 스냅샷(V47, #1702).
     *
     * <p>이전에는 표시·필터 모두 {@code inspection.roundNo}를 실시간으로 읽었는데, #1702가 점검일
     * 소급 입력 시 회차 번호를 재정렬하면서 <b>이미 발급된 PDF 표지의 "제N회차"와 시스템 표기가
     * 어긋나는</b> 문제가 생긴다. 그래서 회차를 보고서 자신의 값으로 스냅샷해 둔다.
     *
     * <p>{@code updatable = false}인 이유: 이 값은 INSERT 시점에 한 번 찍히고, 이후 변경은 오직
     * {@link com.hajacheck.core.report.repository.ReportRepository#syncDraftRoundNoToInspection}
     * (DRAFT 한정 벌크 UPDATE)로만 일어나야 한다. 엔티티 dirty checking이 이 컬럼을 함께 쓰면,
     * 벌크 재동기화와 동시에 진행 중이던 본문 편집이 스냅샷을 옛 값으로 되돌려 놓는다(lost update).
     * {@code Inspection.performedAt}(#1667)이 setter 없이 원자적 UPDATE 경로만 갖는 것과 같은 이유다.
     *
     * <p>FINALIZED는 재동기화 대상이 아니다 — 발급 시점 동결이 이 컬럼의 존재 이유다.
     */
    @Column(name = "round_no", nullable = false, updatable = false)
    private int roundNo;

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

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Builder(access = AccessLevel.PRIVATE)
    private Report(Long inspectionId, int roundNo, int version, String contentJson,
                   Boolean groundingCheckPassed, String groundingWarnings,
                   String pdfUrl, Long editedBy, ReportStatus status, Long createdBy) {
        this.inspectionId = inspectionId;
        this.roundNo = roundNo;
        this.version = version;
        this.contentJson = contentJson;
        this.groundingCheckPassed = groundingCheckPassed;
        this.groundingWarnings = groundingWarnings;
        this.pdfUrl = pdfUrl;
        this.editedBy = editedBy;
        this.status = status == null ? ReportStatus.DRAFT : status;
        this.createdBy = createdBy;
    }

    /**
     * @param roundNo 생성 시점의 점검 회차 스냅샷(#1702) — {@link #roundNo} 참고. 호출부는 항상 대상
     *                점검의 <b>현재</b> 회차를 넘긴다(원본 보고서를 복제할 때도 마찬가지 — 복제본은
     *                새로 발급하는 초안이므로 복제 시점의 회차를 따른다).
     */
    public static Report draft(Long inspectionId, int roundNo, int version, String contentJson, Long createdBy) {
        if (version < 1) {
            throw new DomainValidationException("보고서 버전은 1 이상이어야 한다");
        }
        if (roundNo < 1) {
            throw new DomainValidationException("보고서 회차는 1 이상이어야 한다");
        }
        requireContent(contentJson);
        return Report.builder()
                .inspectionId(inspectionId)
                .roundNo(roundNo)
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

    /** 생성 옵션 적용은 검증된 AI 응답의 표시 범위만 줄이는 작업이므로 grounding 판정을 유지한다. */
    public void applyGeneratedOptions(String contentJson, Long editedBy) {
        requireDraft("applyGeneratedOptions");
        requireContent(contentJson);
        this.contentJson = contentJson;
        this.editedBy = editedBy;
    }

    /** 비동기 Grounding 요청 전에, 아직 생성되지 않은 payload와 분리된 요청 식별자를 캡처한다. */
    public GroundingRequestContext captureGroundingRequestContext() {
        requireDraft("captureGroundingRequestContext");
        return GroundingRequestContext.capture(this.inspectionId, this.version);
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

    /**
     * AI 서버(LLM) 재호출 없이, 현재 저장된 본문(contentJson)이 확정 하자 목록과 구조적으로
     * 일치하는지만 재검증한 결과를 기록한다(#680). {@link #recordGroundingResult}는 AI 응답과
     * 결합된 {@link GroundingCheckResult}/{@link GroundingCheckTarget} 상관관계 검증에 묶여 있어
     * 재사용할 수 없으므로 별도 도메인 메서드로 둔다 — 편집(updateContent)으로 null이 된
     * groundingCheckPassed를 AI 재호출 없이 복구할 유일한 경로다.
     */
    public void recordStructuralGroundingRecheck(boolean matched, String warningsJson, Long editedBy) {
        requireDraft("recordStructuralGroundingRecheck");
        String normalizedWarnings = JsonValidator.normalizeOrRequireValid(
                warningsJson, "근거 재검증 경고(groundingWarnings)");
        this.groundingCheckPassed = matched;
        this.groundingWarnings = normalizedWarnings;
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

    /**
     * 버전 채번 경합(#1653 P3) 재시도 전용 — {@code uk_reports_inspection_version} 유니크 제약 위반으로
     * 저장이 실패했을 때, 이미 검증(grounding 등)을 마친 이 인스턴스를 버리지 않고 새로 배정된 버전
     * 번호로만 다시 저장을 시도한다. version은 같은 inspection 안에서 초안들을 구분하는 순번일 뿐이고
     * grounding 판정은 콘텐츠(contentJson) 자체에 대해 이미 완료된 뒤이므로, AI 상관관계 재검증
     * ({@link #recordGroundingResult})을 다시 거칠 필요가 없다 — 새 버전으로 {@link Report#draft}를
     * 다시 만들면 grounding 결과의 (inspectionId, version, contentJson) 상관관계가 깨져 재검증을 통과할
     * 수 없기 때문에, 검증이 끝난 이 인스턴스의 버전 필드만 교체하는 이 경로가 필요하다.
     */
    public void reassignVersionOnConflictRetry(int version) {
        requireDraft("reassignVersionOnConflictRetry");
        if (version < 1) {
            throw new DomainValidationException("보고서 버전은 1 이상이어야 한다");
        }
        this.version = version;
    }

    /**
     * 버전 채번 경합 재시도 전용(#1702 리뷰 P1) — {@link #reassignVersionOnConflictRetry}와 짝이며, 재시도
     * 직전에 다시 읽은 <b>현재</b> 점검 회차로 스냅샷을 갱신한다. 첫 시도 실패로 시간이 더 흐른 뒤라
     * 처음 찍어 둔 회차가 낡았을 수 있기 때문이다.
     *
     * <p>{@link #roundNo}가 {@code updatable = false}인데도 이 경로가 성립하는 이유: 재시도 대상은 INSERT
     * 자체가 실패해 아직 <b>영속되지 않은</b> 인스턴스다({@code updatable = false}는 UPDATE 문에서 컬럼을
     * 빼는 설정일 뿐 INSERT에는 관여하지 않는다). 이미 저장된 보고서의 회차를 바꾸는 용도로 쓰면 안 된다 —
     * 확정 후 회차 동결이 그 컬럼의 존재 이유이며, DRAFT 재동기화는
     * {@link com.hajacheck.core.report.repository.ReportRepository#syncDraftRoundNoToInspection} 하나뿐이다.
     */
    public void resnapshotRoundNoOnConflictRetry(int roundNo) {
        requireDraft("resnapshotRoundNoOnConflictRetry");
        if (roundNo < 1) {
            throw new DomainValidationException("보고서 회차는 1 이상이어야 한다");
        }
        this.roundNo = roundNo;
    }

    public void markDeleted(Long editedBy) {
        requireDraft("delete");
        if (this.deletedAt != null) {
            return;
        }
        this.deletedAt = LocalDateTime.now();
        this.editedBy = editedBy;
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
