package com.hajacheck.core.defect.entity;

import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.media.entity.Media;
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
 * 점검 이미지에서 탐지되거나 검토된 시설 결함 — DDL defects 테이블 대응.
 * SpringBoot_코드_컨벤션.md §6/§7: @Setter 금지. {@code inspectionId} 는 FK 값 컬럼을 실제 매핑 소스로 두고,
 * 지연 로딩 연관관계({@code inspection})는 조회 전용({@code insertable/updatable = false})으로 병행 제공한다.
 *
 * <p>⚠️ BaseTimeEntity 상속 금지: defects 테이블에는 updated_at 컬럼이 없다(created_at 만 존재).
 * type/grade/status 는 PG named enum — @JdbcTypeCode(NAMED_ENUM) 매핑. grade 는 DDL 상 nullable.
 *
 * <p>mediaId(HAJA-314)는 이 결함이 어느 촬영 이미지에서 탐지됐는지 가리키는 nullable FK다 — bbox 좌표가
 * 있어도 그 좌표가 속한 이미지를 알 수 없어 하자 상세 화면에 실사진을 띄울 방법이 없었다. AI 탐지 파이프라인이
 * 아직 없어 기존 행은 전부 NULL로 남는다(백필 대상 없음).
 *
 * <p>location(#970 갭3)은 하자 위치 텍스트(예: "외벽 동측 12층 부근")다 — 조치 등록 시점이 아니라
 * 검수자가 사후에 편집하는 값이라 생성 시점엔 항상 null이지만, 필드 자체는 단순 nullable 컬럼이라
 * 빌더에도 포함한다(생성 직후 값을 채워 넣는 시드/테스트 편의).
 *
 * <p>previousDefectId(HAJA-437)는 회차 간 비교를 위해 검수자가 화면에서 확정한 이전 회차 대응 하자
 * id(self-referencing FK)다. actionMediaId와 동일한 이유로 빌더에는 포함하지 않는다 — 자동 매칭이
 * 아니라 검수자의 명시적 확정 행위이므로 생성 시점 값이 아니라 {@link #confirmPreviousDefect(Long)}로만 설정한다.
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

    @Column(name = "media_id")
    private Long mediaId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "media_id", insertable = false, updatable = false)
    private Media media;

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

    @Column(name = "area_ratio")
    private Double areaRatio;

    // 조치 결과 등록(HAJA-393/#725, "조치 완료 등록" 버튼) — 4개 필드 모두 registerActionResult() 를
    // 통해서만 함께 채워진다(V12, nullable — 조치 등록 전에는 전부 NULL).
    @Column(name = "action_media_id")
    private Long actionMediaId;

    @Column(name = "action_content")
    private String actionContent;

    @Column(name = "action_date")
    private LocalDate actionDate;

    @Column(name = "action_assignee_id")
    private Long actionAssigneeId;

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(columnDefinition = "text")
    private String location;

    @Column(name = "previous_defect_id")
    private Long previousDefectId;

    @Builder
    private Defect(Long inspectionId, Long mediaId, DefectType type, Double bboxX, Double bboxY, Double bboxW,
                    Double bboxH, Double confidence, DefectGrade grade, DefectStatus status, boolean reviewed,
                    boolean deleted, Double crackWidthMm, Double crackLengthMm, Double areaRatio, String location) {
        this.inspectionId = inspectionId;
        this.mediaId = mediaId;
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
        this.areaRatio = areaRatio;
        this.location = location;
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
     * 상태 전이(HAJA-26 3차: RESOLVED 역행 허용, #1556). 정방향 한 단계 전이는 사유 없이 허용하고,
     * 그 외(역행·건너뛰기) 전이는 {@code reason}이 있어야만 허용한다(PRD FR-4 "역행·건너뛰기는
     * 사유 기록 필수"). 조치완료(RESOLVED)에서 다른 상태로 되돌리는 것도 이 일반 규칙을 그대로
     * 따른다 — RESOLVED의 정방향 다음 단계는 없으므로({@code expectedNext == null}) RESOLVED를
     * 벗어나는 모든 전이는 항상 역행/건너뛰기로 취급돼 사유가 필요하다.
     *
     * <p>과거(HAJA-26 2차)엔 RESOLVED를 사유 유무와 무관하게 이탈 불가한 종료 상태로 취급했으나,
     * "조치완료로 잘못 넘어간 하자를 되돌릴 방법이 없다"는 현장 피드백(#1556)에 따라 완화했다.
     *
     * <p>신규(DETECTED) 이탈에는 등급이 필요하다(#1397) — PRD FR-4가 검수를 "오탐 수정·등급 확정"으로
     * 정의하므로, 등급이 없는 채로 DETECTED를 벗어나면 "검수는 끝났는데 등급은 없는" 상태가 된다.
     * 그 상태는 되돌릴 수 없다: 화면의 등급 수정은 DETECTED에서만 열리고 다른 화면에도 등급 편집
     * UI가 없어, 한 번 확정되면 앱 어디서도 등급을 부여할 수 없는 영구 미분류로 고착된다.
     * 등급 부여는 {@link #review(DefectGrade)}가 status를 바꾸지 않으므로 확정 전에 먼저 하면 된다.
     */
    public void changeStatus(DefectStatus status, String reason) {
        if (status == null) {
            throw new DomainValidationException("changeStatus 불가: 변경할 상태는 필수다");
        }
        requireNotDeleted("changeStatus");

        if (this.status == DefectStatus.DETECTED && this.grade == null) {
            throw new DomainValidationException(
                    "changeStatus 불가: 등급이 없는 신규(DETECTED) 결함은 먼저 등급을 확정해야 한다 (요청 상태=%s)"
                            .formatted(status));
        }
        if (status == this.status) {
            throw new DomainStateTransitionException(
                    "changeStatus 불가: 현재 상태와 동일한 상태로는 전이할 수 없다 (상태=%s)".formatted(status));
        }

        // 정방향 한 단계 판정은 DefectStatus#isForwardStepTo가 단일 기준이다(#1583) — 서비스 계층의
        // 그룹 팬아웃도 같은 메서드를 쓰므로 두 곳의 규칙이 갈라질 수 없다.
        boolean isForwardStep = this.status.isForwardStepTo(status);
        if (!isForwardStep && (reason == null || reason.isBlank())) {
            throw new DomainValidationException(
                    "changeStatus 불가: 역행/건너뛰기 전이는 사유가 필요하다 (현재 상태=%s, 요청 상태=%s)"
                            .formatted(this.status, status));
        }
        this.status = status;
        this.reviewed = true;
    }

    /**
     * 조치 결과 등록(HAJA-393/#725, "상태 저장" 버튼) — 조치 후 사진/조치 내용/조치일/담당자를
     * 한 번에 저장하면서 {@code targetStatus}로 상태를 전이한다. 별도 사유 입력란이 없는 폼이라
     * changeStatus()를 reason 없이 호출한다 — 정방향 한 단계(CONFIRMED→IN_PROGRESS,
     * IN_PROGRESS→RESOLVED)만 사유 없이 허용하는 기존 규칙을 그대로 재사용하므로, 순서를 건너뛴
     * 전이(예: CONFIRMED에서 바로 RESOLVED)는 DomainValidationException으로 자연히 막힌다
     * (조기 완료 방지). 조치 등록의 타겟이 될 수 없는 값(DETECTED/CONFIRMED) 자체를 걸러내는 것은
     * 서비스 계층(DefectService#registerActionResult)의 책임이다.
     *
     * <p>targetStatus == 현재 상태(#1193/HAJA-569)는 조치중(IN_PROGRESS) 단계에서 시간차를 두고 여러
     * 번 등록하는 "진행 중 유지 재제출"에 한해서만 허용한다 — changeStatus()는 동일 상태 재전이를
     * 항상 거부하므로 여기서 우회 분기를 둔다. 그 외(RESOLVED 유지 재제출 포함)는 그대로
     * changeStatus()에 위임해 기존 예외 의미를 보존한다 — 이미 RESOLVED인 하자는 changeStatus()의
     * "RESOLVED 이탈 금지" 검사(동일 상태 검사보다 우선)에 걸려 DomainStateTransitionException으로
     * 막힌다(회귀 방지). flat 필드(actionMediaId 등)는 두 경우 모두 "최신 스냅샷"으로 계속 덮어쓴다
     * (기존 계약 유지 — 이력 자체는 서비스 계층이 DefectActionLog로 별도 append한다).
     *
     * <p><b>⚠️ 위 "이미 RESOLVED인 하자는 막힌다"는 이 메서드를 직접 탈 때만 성립한다(#1591 P2).</b>
     * 이미지 단위 보수 작업 그룹 팬아웃에서 건너뛰기로 판정된 멤버는 이 메서드가 아니라
     * {@link #updateActionResultFields}로 들어와 상태 전이 없이 조치 필드만 갱신된다 — 즉 RESOLVED인
     * 하자의 조치 필드가 <b>같은 사진의 다른 하자(anchor) 제출을 통해</b> 덮어써질 수 있다. 그렇게
     * 하지 않으면 그룹에 RESOLVED 멤버가 하나만 있어도 그 사진의 조치 등록 자체가 영구 불가였기
     * 때문에(#1591) 의도적으로 완화한 것이다. 상태 자체는 여전히 이 메서드를 통해서만 바뀐다.
     */
    public void registerActionResult(Long actionMediaId, String actionContent, LocalDate actionDate,
                                      Long actionAssigneeId, DefectStatus targetStatus) {
        if (targetStatus == DefectStatus.IN_PROGRESS && this.status == DefectStatus.IN_PROGRESS) {
            requireNotDeleted("registerActionResult");
        } else {
            changeStatus(targetStatus);
        }
        applyActionResultFields(actionMediaId, actionContent, actionDate, actionAssigneeId);
    }

    /**
     * 조치 결과의 <b>필드만</b> 반영한다 — 상태 전이는 하지 않는다(#1591 P2).
     *
     * <p>이미지 단위 보수 작업 그룹 팬아웃에서 {@code DefectService#shouldSkipGroupMember} 로 상태
     * 전이를 건너뛰기로 판정된 멤버(이미 목표 상태이거나 목표보다 앞서 있거나, 목표까지 두 단계 이상
     * 뒤처진 하자)에 쓴다. 그 멤버도 <b>같은 사진에 대한 조치 등록의 대상</b>이므로 조치 사진·내용·
     * 조치일·담당자는 그대로 기록해야 한다 — 상태만 제자리에 둔다.
     *
     * <p>{@link #changeStatus}를 거치지 않으므로 {@code reviewed} 플래그도 건드리지 않는다(상태가
     * 안 바뀌었으니 검수 여부도 그대로다).
     */
    public void updateActionResultFields(Long actionMediaId, String actionContent, LocalDate actionDate,
                                          Long actionAssigneeId) {
        requireNotDeleted("updateActionResultFields");
        applyActionResultFields(actionMediaId, actionContent, actionDate, actionAssigneeId);
    }

    private void applyActionResultFields(Long actionMediaId, String actionContent, LocalDate actionDate,
                                          Long actionAssigneeId) {
        this.actionMediaId = actionMediaId;
        this.actionContent = actionContent;
        this.actionDate = actionDate;
        this.actionAssigneeId = actionAssigneeId;
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

    /**
     * 하자 위치 사후 편집(#970 갭3) — 조치 등록 흐름과 분리된 가벼운 편집이라 상태 전이 규칙과 무관하게
     * 삭제되지 않은 하자라면 언제든 허용한다. 빈 문자열/공백은 null로 정규화한다(호출부가 지우기 위해
     * 빈 문자열을 보내는 경우와 실제 null을 구분할 필요가 없음).
     */
    public void updateLocation(String location) {
        requireNotDeleted("updateLocation");
        this.location = (location == null || location.isBlank()) ? null : location;
    }

    /**
     * 회차 간 대응 하자 확정(HAJA-437) — 검수자가 화면에서 확인한 이전 회차 하자 id를 저장한다.
     * 같은 시설물·더 이전 회차인지 등 참조 유효성 검증은 서비스 계층(DefectService)이 수행하고,
     * 이 메서드는 순수하게 값을 반영만 한다(actionMediaId 등 다른 "확정 행위" 필드와 동일 원칙 —
     * 자동 매칭이 아닌 사람의 명시적 확정이므로 빌더가 아닌 별도 메서드로만 설정).
     */
    public void confirmPreviousDefect(Long previousDefectId) {
        requireNotDeleted("confirmPreviousDefect");
        this.previousDefectId = previousDefectId;
    }

    /**
     * 오탐 삭제(soft delete). {@code reviewed}는 건드리지 않는다(실측 버그 수정) — AI 분석이
     * 하자 생성 시점에 이미 {@code grade}를 채워 넣으므로({@link com.hajacheck.core.analysis.service.InspectionAnalysisWorker})
     * "등급 유무"는 "사람이 검수했는가"의 신호가 될 수 없고, 유일한 소비처인
     * {@link com.hajacheck.core.defect.repository.DefectRepository#existsByInspectionIdAndDeletedFalseAndReviewedFalse}와
     * 프론트 reviewedCount(useInspectionResultReal.ts)는 둘 다 {@code deleted=false}만 보므로
     * 삭제된 행의 {@code reviewed} 값은 삭제돼 있는 동안은 아무도 읽지 않는다 — 강제로 바꿀 이유가
     * 없고, 바꾸면 {@link #restore()}가 원래 상태를 복원할 방법이 없어진다(과거 값을 덮어써 버림).
     */
    public void softDelete() {
        if (this.deleted) {
            return;
        }
        this.deleted = true;
    }

    /**
     * 오탐 삭제 복구(#1399) — 잘못 지운 하자를 되돌린다. soft delete라 데이터는 그대로 살아 있고
     * 플래그만 되돌리면 되며, 삭제 사유 이력({@code defect_revisions})도 append-only라 보존된다.
     *
     * <p>{@code reviewed}는 건드리지 않는다 — {@link #softDelete()}가 더 이상 그 값을 덮어쓰지
     * 않으므로, 삭제 전 상태(사람이 실제로 검수했으면 true, 미확정이었으면 false)가 그대로
     * 보존돼 있다. "되살릴 자격이 있는 삭제인지"(검수자 오탐 판정 vs 재분석 소프트삭제)는 이력을
     * 아는 서비스 계층이 판정한다.
     */
    public void restore() {
        if (!this.deleted) {
            return;
        }
        this.deleted = false;
    }

    private void requireNotDeleted(String action) {
        if (this.deleted) {
            throw new DomainStateTransitionException(
                    "%s 불가: 삭제된 결함은 변경할 수 없다".formatted(action));
        }
    }
}
