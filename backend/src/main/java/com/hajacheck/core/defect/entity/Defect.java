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
            case CONFIRMED -> DefectStatus.IN_PROGRESS;
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
     */
    public void registerActionResult(Long actionMediaId, String actionContent, LocalDate actionDate,
                                      Long actionAssigneeId, DefectStatus targetStatus) {
        if (targetStatus == DefectStatus.IN_PROGRESS && this.status == DefectStatus.IN_PROGRESS) {
            requireNotDeleted("registerActionResult");
        } else {
            changeStatus(targetStatus);
        }
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

    public void softDelete() {
        if (this.deleted) {
            return;
        }
        this.deleted = true;
        this.reviewed = true;
    }

    private void requireNotDeleted(String action) {
        if (this.deleted) {
            throw new DomainStateTransitionException(
                    "%s 불가: 삭제된 결함은 변경할 수 없다".formatted(action));
        }
    }
}
