package com.hajacheck.core.inspection.entity;

import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.global.common.KstFixedLocalDateTimeConverter;
import com.hajacheck.global.exception.DomainStateTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import jakarta.persistence.UniqueConstraint;
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
 * 시설별 점검 회차와 진행 상태 — DDL inspections 테이블 대응.
 * SpringBoot_코드_컨벤션.md §6/§7: @Setter 금지. {@code facilityId} 는 FK 값 컬럼을 실제 매핑 소스로 두고,
 * 지연 로딩 연관관계({@code facility})는 조회 전용({@code insertable/updatable = false})으로 병행 제공한다.
 * 도메인 경계를 넘는 {@code createdBy}/{@code assignedInspectorId}(auth 도메인 User 참조)는 Long 값만 보유한다.
 *
 * <p>⚠️ BaseTimeEntity 상속 금지: inspections 테이블에는 updated_at 컬럼이 없다(created_at 만 존재).
 * type/status 는 PG named enum(inspection_type/inspection_status_type) — @JdbcTypeCode(NAMED_ENUM) 매핑.
 *
 * <p>assignedInspectorId(점검 담당자)는 DB 상 not null이며 애플리케이션에서
 * users.status=ACTIVE AND role IN (INSPECTOR, ADMIN) 검증을 거친 값만 들어온다
 * (docs/design/db/table_design.md §inspections — createdBy를 근거 없이 자동 복사하지 않음).
 */
@Entity
@Getter
@Table(
        name = "inspections",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_inspections_facility_round",
                columnNames = {"facility_id", "round_no"}),
        indexes = {
                // #1679(V45) — idx_inspections_facility(facility_id 단일)는 아래 복합 인덱스의 선두
                // 컬럼에 완전 포함되는 중복이라 drop했다. ddl-auto=validate라 이 @Index 목록은 스키마
                // 생성에 관여하지 않지만(Flyway가 실제 DDL을 관리) 엔티티 문서가 실제 인덱스와
                // 어긋나지 않도록 함께 갱신한다.
                @Index(name = "idx_inspections_assigned_inspector", columnList = "assigned_inspector_id"),
                // columnList는 JPA @Index 표현의 한계로 "nulls last"를 담지 못한다(desc만 표현 가능) —
                // 실제 정렬·정합 소스는 V45 SQL(performed_at desc nulls last)이며, 이 columnList는 그
                // 근사치일 뿐이다.
                @Index(name = "idx_inspections_recent_order",
                        columnList = "facility_id, inspection_date desc, performed_at desc, id desc")
        })
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Inspection {

    // id: PG generated always as identity → IDENTITY 전략
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "facility_id", nullable = false)
    private Long facilityId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "facility_id", insertable = false, updatable = false)
    private Facility facility;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "assigned_inspector_id", nullable = false)
    private Long assignedInspectorId;

    @Column(name = "round_no", nullable = false)
    private Integer roundNo;

    @Column(name = "inspection_date", nullable = false)
    private LocalDate inspectionDate;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "inspection_type", nullable = false)
    private InspectionType type;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "inspection_status_type", nullable = false)
    private InspectionStatus status;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 점검 실제 수행 시각(V43, #1667) — 동일 {@code inspectionDate}(날짜만 저장) 내 여러 회차의 실제
     * 수행 순서를 보장하기 위한 tie-break 값. 사용자 입력 UI는 없고, 회차의 INSPECTION_SOURCE 미디어
     * 업로드 시 {@link com.hajacheck.core.inspection.repository.InspectionRepository#applyPerformedAtIfEarlier}
     * 원자적 UPDATE로 자동 세팅된다(MediaWriter). nullable — 미디어가 아직 없는 회차는 null이며, 정렬 시
     * {@code nulls last}로 취급한다(InspectionRepository 참고).
     *
     * <p>코드 리뷰 P1 — 엔티티 read-modify-write(dirty checking) 대신 원자적 조건부 UPDATE로만 갱신한다.
     * 배치 업로드 두 건이 동시에 같은 회차에 미디어를 올리면 두 트랜잭션이 같은 스냅샷을 읽고 각자
     * dirty-check flush하는 lost update가 가능했다(나중에 커밋되는 쪽이 먼저 커밋된 더 이른 값을 조용히
     * 덮어씀) — startAnalyzingIfNotRunning/confirmReviewIfAnalyzed와 동일한 조건부 UPDATE 원칙으로
     * 막는다. 이 엔티티에는 그래서 setter도 mutate 메서드도 없다 — 도메인 캡슐화가 아니라 값 자체가
     * 원자적 UPDATE 경로로만 갱신돼야 하기 때문.
     *
     * <p>코드 리뷰 P1 — {@link com.hajacheck.core.media.entity.Media#getCapturedAt()}과 동일하게
     * {@link KstFixedLocalDateTimeConverter}로 KST 고정 해석을 명시한다. 이 컨버터가 없으면 JVM 기본
     * TZ가 운영(Asia/Seoul 고정)과 테스트/로컬(보장 없음)에서 갈릴 때 captured_at(컨버터 적용)과
     * performed_at(컨버터 미적용) 사이에 절대시각 계산이 어긋나, tie-break 정렬·원자적 UPDATE의 대소
     * 비교가 실제로는 같은 순간을 가리키는 값끼리도 다르게 비교될 수 있었다.
     */
    @Convert(converter = KstFixedLocalDateTimeConverter.class)
    @Column(name = "performed_at")
    private LocalDateTime performedAt;

    @Builder
    private Inspection(Long facilityId, Long createdBy, Long assignedInspectorId, Integer roundNo,
                        LocalDate inspectionDate, InspectionType type, InspectionStatus status,
                        LocalDateTime performedAt) {
        this.facilityId = facilityId;
        this.createdBy = createdBy;
        this.assignedInspectorId = assignedInspectorId;
        this.roundNo = roundNo;
        this.inspectionDate = inspectionDate;
        this.type = type == null ? InspectionType.REGULAR : type;
        this.status = status == null ? InspectionStatus.CREATED : status;
        this.performedAt = performedAt;
    }

    /**
     * 상태 전이 — {@link InspectionStatus} 허용 전이 테이블에 있는 전이만 적용하고, 그 외에는
     * {@link DomainStateTransitionException}으로 거부한다(상태 머신 중앙화). 검증 없는 setter였을 때는
     * 리퍼가 되돌린 회차를 좀비 워커가 ANALYZED로 되살리는 등 불법 전이가 조용히 적용됐다 —
     * 이제는 거부(fail-safe: 상태 불변, 데이터 손상 없음)된다. 정상 경로의 전이는 모두 테이블에
     * 포함돼 있고(그 클래스 주석 참고), 이 예외는 동시성 경쟁으로 이미 다른 경로가 상태를 바꾼
     * 드문 경우에만 발생한다.
     *
     * <p>코드 리뷰 P2(2차) — 표준 {@code IllegalStateException}이 아니라 그 하위형인
     * {@link DomainStateTransitionException}을 던진다. {@code GlobalExceptionHandler}는 표준
     * {@code IllegalStateException}을 "프로그래밍 오류일 수 있음"으로 보아 500으로 처리하고,
     * {@code DomainStateTransitionException}만 명시적으로 409(INVALID_STATE_TRANSITION)로
     * 매핑한다(Company/Defect 등 다른 엔티티의 상태 전이 가드와 동일 패턴). 이 전이 거부는
     * 프로그래밍 오류가 아니라 예상된 동시성 경쟁(예: 고착 복구 재시도 중복 클릭)이므로 409가 맞다.
     */
    public void advanceTo(InspectionStatus next) {
        if (!this.status.canTransitionTo(next)) {
            throw new DomainStateTransitionException(
                    "허용되지 않은 점검 상태 전이: " + this.status + " -> " + next + " (inspectionId=" + this.id + ")");
        }
        this.status = next;
    }
}
