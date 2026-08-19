package com.hajacheck.auth.entity;

import com.hajacheck.global.common.BaseTimeEntity;
import com.hajacheck.global.exception.DomainStateTransitionException;
import com.hajacheck.global.util.JsonValidator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 기업(회사) 계정 — DDL companies 테이블 대응. 기업 회원가입으로 생성된다.
 *
 * <p>User 와의 결합: {@code ownerUserId}/{@code reviewedBy} 는 FK 값 컬럼을 실제 매핑 소스로 두고,
 * 지연 로딩 연관관계({@code ownerUser}/{@code reviewer})는 조회 전용({@code insertable/updatable = false})으로
 * 병행 제공한다(양방향 엔티티 결합은 여전히 금지 — User 쪽에서 Company 를 역참조하지 않는다).
 *
 * <p>enum(verification_status/status) 은 PG named enum 타입이며 {@code @JdbcTypeCode(NAMED_ENUM)} +
 * columnDefinition 으로 실 PG enum 에 매핑한다(ddl-auto=validate 통과). Java enum 라벨은 v0.3 DDL 과 일치.
 *
 * <p>OCR: 현재 stub(수동입력). {@code businessRegistrationOcrRaw} 는 jsonb 원본(감사·재처리용)이다.
 *
 * <p><b>⚠️ {@code businessRegistrationOcrRaw} 는 감사 필수 키를 함께 담는다(#1324) — 컬럼을 통째로
 * 교체하지 말고 반드시 병합할 것.</b> 컬럼명이 "ocr_raw" 라 나중에 실제 OCR 연동이 들어올 때 통째로
 * 덮어쓰기 쉬운데, 그 순간 아래 키들이 사라지고 "국세청 검증을 증명할 수 있는 회사"를 영원히 재구성할
 * 수 없게 된다(#1324 자동승인으로 {@code verificationStatus} 는 전건 VERIFIED 라 구분 근거가 이 컬럼뿐이다).
 * <ul>
 *   <li>{@code source} — OCR 출처(현재 {@code MANUAL_INPUT} stub)</li>
 *   <li>{@code ntsOutcome} — 국세청 진위확인 provenance. {@link #isNtsVerified()} 의 판정 근거</li>
 *   <li>{@code ntsCheckedAt}(신규 가입) / {@code ntsBackfilledAt}(V38 소급) — 기록 시각.
 *       <b>두 키는 출처가 달라 이름이 갈린다</b>(실제 조회 시각 vs 소급 스탬프 시각)</li>
 *   <li>{@code ntsOutcomeBeforeRevoke}/{@code ntsOutcomeBeforeOverride} · {@code adminRevokedAt}/
 *       {@code adminRevokeReason}/{@code adminRevokedBy} · {@code adminRestoredAt}/
 *       {@code adminRestoreReason}/{@code adminRestoredBy} · {@code adminOverriddenAt}/
 *       {@code adminOverrideReason}/{@code adminOverriddenBy} — 플랫폼 관리자 무효화/복구/강제개방
 *       감사(#1367). {@link #revokeBusinessVerificationByAdmin} /
 *       {@link #restoreBusinessVerificationByAdmin} / {@link #overrideBusinessVerificationByAdmin} 참고.
 *       actor 는 <b>id 만</b> 담는다(이메일·이름 금지)</li>
 *   <li>{@code ntsLastAttemptAt} · {@code ntsLastAlertOutcome} · {@code ntsLastAlertAt} — 재검증 배치의
 *       처리 시도·경보 기록(#1367). 대기열 라운드로빈 정렬 축이자 "경보만" 정책의 유일한 영속 신호다</li>
 * </ul>
 *
 * <p><b>⚠️ {@code ntsOutcome} 의 값 공간은 enum 이 아니다</b> — {@code valueOf()} 로 파싱하면 터진다.
 * {@code NtsVerificationOutcome} 라벨(VERIFIED/SKIPPED/MISMATCH/…)에 더해 마이그레이션이 심는
 * {@code UNKNOWN_BACKFILL}(V38 소급 승인분 = 검증한 적 없음) · {@code LEGACY_VERIFIED}(#1324 이전에
 * 진짜 검증을 통과한 기존 회사) 가 섞이고, <b>키 자체가 없는</b> 행도 있다(컬럼 null · 직렬화 실패
 * fallback · 외부에서 쓴 값). 문자열로 다루고 화이트리스트로 판정한다.
 */
@Entity
@Getter
@Table(name = "companies")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Company extends BaseTimeEntity {

    /** {@code businessRegistrationOcrRaw} 안에서 국세청 진위확인 provenance 를 담는 키. */
    private static final String NTS_OUTCOME_FIELD = "ntsOutcome";

    /**
     * 실제 국세청 조회 시각을 담는 키 — {@code CompanySignupService.buildOcrRaw} 와 <b>같은 규약</b>이다
     * (V38 소급 스탬프는 출처가 달라 {@code ntsBackfilledAt} 을 쓴다 — 클래스 javadoc 참고).
     */
    private static final String NTS_CHECKED_AT_FIELD = "ntsCheckedAt";

    /** 국세청이 진위를 확인해 준 경우의 {@code ntsOutcome} 값({@code NtsVerificationOutcome.VERIFIED} 라벨). */
    private static final String NTS_OUTCOME_VERIFIED = "VERIFIED";

    /**
     * 플랫폼 관리자가 사람 판단으로 검증을 무효화했음을 뜻하는 {@code ntsOutcome} 값(#1367).
     * 화이트리스트({@link #NTS_VERIFIED_OUTCOMES}) 밖이라 {@link #isNtsVerified()} 가 false 가 된다.
     */
    private static final String NTS_OUTCOME_ADMIN_REVOKED = "ADMIN_REVOKED";

    /**
     * 플랫폼 관리자가 무효화를 되돌려 <b>재검증 대상으로 복귀</b>시켰음을 뜻하는 {@code ntsOutcome} 값
     * (#1367, {@link AdminRestoreMode#RESTORED_TO_PENDING} 경로 전용). 이것 역시 화이트리스트 밖이다 —
     * 관리자는 "차단을 푼다"고 말할 수 있을 뿐 "국세청이 확인해 줬다"고 말할 수 없다. 배지는 꺼진 채
     * 유지되고, 재검증 배치가 국세청 확인에 성공하면 {@link #markBusinessVerifiedByNts()} 가 켠다.
     *
     * <p>관리자 자기 조치의 순수 취소({@link AdminRestoreMode#RESTORED_TO_VERIFIED})는 이 값을 쓰지 않고
     * <b>무효화 직전 값을 그대로 원복</b>한다 — 되무르는 것이지 새 판정을 만드는 것이 아니다.
     */
    private static final String NTS_OUTCOME_ADMIN_RESTORED = "ADMIN_RESTORED";

    /**
     * 관리자가 국세청 판정과 무관하게 회사 스코프를 연 상태를 뜻하는 {@code ntsOutcome} 값(#1367).
     *
     * <p><b>화이트리스트({@link #NTS_VERIFIED_OUTCOMES}) 밖이어야 한다 — 절대 넣지 말 것.</b> 두 성질이
     * 동시에 필요하기 때문이다: ①{@link #isNtsVerified()} 가 false → 국세청이 확인해 준 게 아니므로
     * "사업자 인증 완료" 배지를 켜지 않는다 ②{@code CompanyRepository#findNtsReverifyTargets} 의 두 번째
     * 갈래(VERIFIED ∧ 화이트리스트 밖)에 <b>계속 잡힌다</b> → 국세청이 나중에 미등록·폐업을 확정하면
     * 배치가 <b>자동으로 다시 차단</b>한다. 이 자동 재차단이 override 의 유일한 안전장치이며, 화이트리스트에
     * 넣는 순간 사라진다.
     */
    private static final String NTS_OUTCOME_ADMIN_OVERRIDE = "ADMIN_OVERRIDE_VERIFIED";

    /** 무효화 직전 {@code ntsOutcome} 을 보존하는 키(#1367) — 덮어쓰기로 감사 기록이 사라지지 않게 한다. */
    private static final String NTS_OUTCOME_BEFORE_REVOKE_FIELD = "ntsOutcomeBeforeRevoke";

    /** override 직전 {@code ntsOutcome} 을 보존하는 키(#1367). */
    private static final String NTS_OUTCOME_BEFORE_OVERRIDE_FIELD = "ntsOutcomeBeforeOverride";

    /**
     * 관리자 무효화 시각·사유·actor 키(#1367). actor 를 <b>DB 에도</b> 남긴다 — 로그에만 두면 prod 로그
     * 보존 한계(이 배치의 실사고에서 이미 겪었다)로 "누가 막았는가"를 사후에 재구성할 수 없다.
     * <b>식별자만</b> 넣는다(이메일·이름 금지).
     */
    private static final String ADMIN_REVOKED_AT_FIELD = "adminRevokedAt";
    private static final String ADMIN_REVOKE_REASON_FIELD = "adminRevokeReason";
    private static final String ADMIN_REVOKED_BY_FIELD = "adminRevokedBy";

    /** 관리자 복구 시각·사유·actor 키(#1367). */
    private static final String ADMIN_RESTORED_AT_FIELD = "adminRestoredAt";
    private static final String ADMIN_RESTORE_REASON_FIELD = "adminRestoreReason";
    private static final String ADMIN_RESTORED_BY_FIELD = "adminRestoredBy";

    /** 관리자 override 시각·사유·actor 키(#1367). */
    private static final String ADMIN_OVERRIDDEN_AT_FIELD = "adminOverriddenAt";
    private static final String ADMIN_OVERRIDE_REASON_FIELD = "adminOverrideReason";
    private static final String ADMIN_OVERRIDDEN_BY_FIELD = "adminOverriddenBy";

    /**
     * 재검증 배치가 이 회사를 <b>마지막으로 처리 시도한</b> 시각 키(#1367 P1-C, ISO-8601).
     *
     * <p>대기열 라운드로빈의 정렬 축이다 — {@code findNtsReverifyTargets} 가 이 값 오름차순(미시도=빈
     * 문자열이 먼저)으로 정렬하므로, 상태가 변하지 않아 집합에 영구 거주하는 회사(MISMATCH/SUSPENDED ·
     * 데모)가 매 회차 큐 앞자리를 고정 점유해 신규 가입 회사를 밀어내는 것을 막는다.
     *
     * <p>⚠️ 스탬프는 {@code ntsOutcome} 을 <b>절대</b> 건드리지 않는다 — 그 키는 대상 집합(where 절)을
     * 결정하므로, 스탬프가 집합을 바꾸면 정렬 개선이 아니라 통제 파괴가 된다.
     */
    private static final String NTS_LAST_ATTEMPT_AT_FIELD = "ntsLastAttemptAt";

    /**
     * 자동 강등하지 않는 경보 판정(MISMATCH/SUSPENDED)의 마지막 값·시각 키(#1367 P2-3).
     *
     * <p>"경보만 남기고 상태는 두는" 정책은 사람 판단으로 통제를 옮긴 것인데, 경보가 DB 에 하나도
     * 남지 않으면 <b>사람에게 도달하는 신호가 없다</b>(진단 API 로도 "어제 MISMATCH 를 받았다"를 알 수
     * 없다 — {@code ntsOutcome} 은 옛 값 그대로다). 그래서 판정 자체는 이 별도 키에 기록한다.
     */
    private static final String NTS_LAST_ALERT_OUTCOME_FIELD = "ntsLastAlertOutcome";
    private static final String NTS_LAST_ALERT_AT_FIELD = "ntsLastAlertAt";

    /**
     * "국세청 검증을 증명할 수 있다"로 인정하는 {@code ntsOutcome} 값 화이트리스트({@link #isNtsVerified}).
     * {@code LEGACY_VERIFIED} 는 V38 이 스탬프한다 — #1324 이전에는 VERIFIED 가 오직 가입 시 국세청
     * 성공 또는 #888 재검증 성공으로만 찍혔으므로, 그 시점의 VERIFIED 는 진짜 검증이다.
     */
    private static final Set<String> NTS_VERIFIED_OUTCOMES = Set.of(NTS_OUTCOME_VERIFIED, "LEGACY_VERIFIED");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    // 기업 계정 소유자(플랜 보유자) 사용자 식별자 — FK 값 컬럼(쓰기 소스), 아래 ownerUser 는 조회 전용 병행 매핑.
    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_user_id", insertable = false, updatable = false)
    private User ownerUser;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "business_registration_number", nullable = false, unique = true, length = 20)
    private String businessRegistrationNumber;

    @Column(name = "representative_name", nullable = false, length = 100)
    private String representativeName;

    // 개업일자(국세청 진위확인 파라미터). 기존 회사 backfill 을 위해 nullable(신규 가입은 항상 채워진다).
    @Column(name = "business_start_date")
    private LocalDate businessStartDate;

    @Column(nullable = false, length = 300)
    private String address;

    @Column(name = "address_detail", length = 200)
    private String addressDetail;

    @Column(name = "business_registration_file_url", nullable = false, length = 500)
    private String businessRegistrationFileUrl;

    // jsonb — OCR 추출 원본(현재 stub). String 으로 보관하고 @JdbcTypeCode(JSON) 로 jsonb 매핑.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "business_registration_ocr_raw", columnDefinition = "jsonb")
    private String businessRegistrationOcrRaw;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "verification_status", columnDefinition = "business_verification_status_type", nullable = false)
    private BusinessVerificationStatus verificationStatus;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "company_status_type", nullable = false)
    private CompanyStatus status;

    // 승인/반려 처리 관리자 식별자 — FK 값 컬럼(쓰기 소스), 아래 reviewer 는 조회 전용 병행 매핑.
    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by", insertable = false, updatable = false)
    private User reviewer;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Builder(access = AccessLevel.PRIVATE)
    private Company(Long ownerUserId, String name, String businessRegistrationNumber,
                    String representativeName, LocalDate businessStartDate, String address, String addressDetail,
                    String businessRegistrationFileUrl, String businessRegistrationOcrRaw,
                    BusinessVerificationStatus verificationStatus, CompanyStatus status) {
        this.ownerUserId = ownerUserId;
        this.name = name;
        this.businessRegistrationNumber = businessRegistrationNumber;
        this.representativeName = representativeName;
        this.businessStartDate = businessStartDate;
        this.address = address;
        this.addressDetail = addressDetail;
        this.businessRegistrationFileUrl = businessRegistrationFileUrl;
        this.businessRegistrationOcrRaw = businessRegistrationOcrRaw;
        this.verificationStatus = verificationStatus == null ? BusinessVerificationStatus.PENDING : verificationStatus;
        this.status = status == null ? CompanyStatus.PENDING_REVIEW : status;
    }

    /**
     * 가입 신청 팩토리(개업일자 미보유 오버로드 — 테스트 픽스처·기존 호출부 호환).
     * 진위확인 PENDING, 승인 PENDING_REVIEW 로 생성.
     */
    public static Company createPendingReview(Long ownerUserId, String name, String businessRegistrationNumber,
                                              String representativeName, String address, String addressDetail,
                                              String businessRegistrationFileUrl, String businessRegistrationOcrRaw) {
        return createPendingReview(ownerUserId, name, businessRegistrationNumber, representativeName,
                address, addressDetail, businessRegistrationFileUrl, businessRegistrationOcrRaw, null);
    }

    /**
     * 가입 신청 팩토리 — 진위확인 PENDING, 승인 PENDING_REVIEW 로 생성.
     * OCR 은 stub 값(호출부에서 {@code {"source":"MANUAL_INPUT"}} 전달). {@code businessStartDate} 는
     * 국세청 진위확인 파라미터로 신규 가입 시 항상 전달된다(#596).
     *
     * <p>이 팩토리는 "신청 시점의 초기 상태"만 만든다 — 실제 가입 경로
     * ({@code CompanyAccountWriter#createAccount})는 생성 직후 같은 트랜잭션에서
     * {@link #markBusinessVerified()} + {@link #autoApprove()} 로 VERIFIED/APPROVED 로 전이시킨다(#1324).
     * 팩토리 이름·의미와 기존 호출부·테스트를 지키기 위해 초기 상태는 그대로 두고 전이는 writer 가 맡는다.
     */
    public static Company createPendingReview(Long ownerUserId, String name, String businessRegistrationNumber,
                                              String representativeName, String address, String addressDetail,
                                              String businessRegistrationFileUrl, String businessRegistrationOcrRaw,
                                              LocalDate businessStartDate) {
        String normalizedOcrRaw = JsonValidator.normalizeOrRequireValid(
                businessRegistrationOcrRaw, "OCR 원본(businessRegistrationOcrRaw)");
        return Company.builder()
                .ownerUserId(ownerUserId)
                .name(name)
                .businessRegistrationNumber(businessRegistrationNumber)
                .representativeName(representativeName)
                .businessStartDate(businessStartDate)
                .address(address)
                .addressDetail(addressDetail)
                .businessRegistrationFileUrl(businessRegistrationFileUrl)
                .businessRegistrationOcrRaw(normalizedOcrRaw)
                .verificationStatus(BusinessVerificationStatus.PENDING)
                .status(CompanyStatus.PENDING_REVIEW)
                .build();
    }

    /**
     * 관리자 승인 (상태 전이 — 현재 미배선, 관리자 승인 화면 후속 과제).
     *
     * <p>⚠️ 계약: {@code Company.status}와 {@code CompanyMembership.status}는 독립된 두 상태 머신이다(HAJA-25 P2).
     * 이 메서드를 서비스 계층에 배선할 때는 같은 트랜잭션에서 오너의 {@link CompanyMembership}도 함께
     * {@code APPROVED}로 전이(신규 발급 또는 기존 PENDING 행의 {@code approve()})시켜야 한다. 그렇지 않으면
     * 회사는 승인되었지만 오너에게는 유효한 소속 멤버십이 없는 상태 불일치가 생긴다(migration의 finalize/verify가
     * 검증하는 "APPROVED+VERIFIED 회사는 유효한 오너 멤버십을 가져야 한다" 불변식과 충돌).
     */
    public void approve(Long reviewerUserId) {
        requirePendingReview("approve");
        if (this.verificationStatus != BusinessVerificationStatus.VERIFIED) {
            throw new DomainStateTransitionException(
                    "approve 불가: 사업자등록정보 검증이 완료된 회사만 승인할 수 있다");
        }
        this.status = CompanyStatus.APPROVED;
        this.reviewedBy = reviewerUserId;
        this.reviewedAt = Instant.now();
        this.rejectionReason = null;
    }

    /**
     * 가입 즉시 자동승인(#1324) — 사람 심사자 없이 시스템이 승인한다.
     *
     * <p><b>근거</b>: 관리자 승인 화면·API 가 아직 배선되지 않았고({@link #approve(Long)} 호출부 0건),
     * 프론트는 이미 승인 대기 단계를 제거해 "가입 완료 → 로그인" 흐름이다. 그대로 두면 신규 가입 기업이
     * 영구 {@code PENDING_REVIEW} 로 남아 회사 스코프 기능(점검 생성·담당자 배정)이 전혀 열리지 않는다
     * (스코프 판정은 회사 {@code APPROVED} + {@code VERIFIED} + 오너의 유효 멤버십을 모두 요구한다 —
     * {@code CompanyMembershipRepository.existsEffectiveApprovedMembership}).
     *
     * <p><b>{@link #approve(Long)} 와 분리한 이유</b>: 관리자 수동 승인은 "{@code VERIFIED} 선행" 가드를
     * 유지해야 한다(향후 배선). 자동승인은 진위확인 결과와 무관하게 통과시키는 운영 결정이므로 그 가드를
     * 느슨하게 풀지 않고 별 경로로 둔다. 진위확인 상태 승격은 {@link #markBusinessVerified()} 책임이다.
     *
     * <p>{@code reviewedBy = null} 은 "사람 심사자 없음(시스템 자동승인)"을 뜻한다
     * (companies.reviewed_by 는 nullable). {@code PENDING_REVIEW} 에서만 호출 가능하므로 반려된 회사가
     * 자동승인으로 되살아나지 않는다.
     *
     * <p>⚠️ 계약: {@link #approve(Long)} 와 동일하게, 호출부는 <b>같은 트랜잭션에서 오너의</b>
     * {@link CompanyMembership} <b>도 APPROVED 로 발급</b>해야 한다 — 회사만 승인하고 멤버십을 빼면
     * "승인된 회사인데 오너에게 유효 소속이 없는" 불일치가 남아 스코프가 여전히 닫힌다
     * (배선 지점: {@code CompanyAccountWriter#createAccount}).
     */
    public void autoApprove() {
        requirePendingReview("autoApprove");
        this.status = CompanyStatus.APPROVED;
        this.reviewedBy = null;
        this.reviewedAt = Instant.now();
        this.rejectionReason = null;
    }

    /**
     * 관리자 반려 (상태 전이 — 현재 미배선).
     *
     * <p>⚠️ 계약: {@link #approve(Long)}와 동일하게, 이 회사에 이미 {@code PENDING} {@link CompanyMembership}
     * 초대가 존재한다면(예: 재심사 흐름) 반려 시 함께 {@code REJECTED}로 정리할지 서비스 계층에서 결정해야
     * 한다 — 두 상태 머신을 독립적으로 갱신하면 회사는 반려됐는데 멤버십은 대기 상태로 남는 불일치가 생긴다.
     */
    public void reject(Long reviewerUserId, String reason) {
        requirePendingReview("reject");
        this.status = CompanyStatus.REJECTED;
        this.reviewedBy = reviewerUserId;
        this.reviewedAt = Instant.now();
        this.rejectionReason = reason;
    }

    /**
     * <b>국세청 진위확인을 실제로 통과했음을 증명할 수 있는가</b>(#1324 P1) — 사용자 대면
     * "사업자 인증 완료" 배지({@code MyPlanResponse.PlanInfo#businessVerified})의 유일한 판정 근거다.
     *
     * <p><b>왜 {@code verificationStatus} 를 쓰지 않는가</b>: #1324 자동승인이 진위확인 결과와 무관하게
     * 전건 VERIFIED 를 찍으므로, 그 컬럼은 이제 <b>"회사 스코프를 열어도 되는가"</b>(인가 플래그)만
     * 뜻한다. 그대로 배지에 쓰면 국세청 장애·키 미설정으로 통과한 회사(SKIPPED)와 V38 소급분
     * (UNKNOWN_BACKFILL, 검증한 적 없음)에까지 "사업자 인증 완료"라는 <b>허위 사실을 표시</b>하게 된다.
     * 그래서 배지는 인가 플래그가 아니라 provenance({@code ocr_raw.ntsOutcome})로 판정한다.
     *
     * <p><b>화이트리스트 + fail-safe</b>: {@code VERIFIED}(신규 가입 시 국세청 성공) ·
     * {@code LEGACY_VERIFIED}(#1324 이전에 진짜 검증을 통과해 V38 이 스탬프한 기존 회사)만 true.
     * 그 밖의 값·키 부재·JSON 파손은 모두 <b>false</b>다 — "증명할 수 없으면 인증 완료라고 말하지
     * 않는다"가 이 배지의 계약이고, 조회 경로라 예외를 던져 500 을 만들어서도 안 된다.
     *
     * <p>⚠️ 이 메서드는 <b>표시 전용</b>이다. 인가 판정(스코프 개방)은 여전히
     * {@code CompanyMembershipRepository.existsEffectiveApprovedMembership} 의 VERIFIED 조건이 맡는다 —
     * 둘을 뒤바꾸면 자동승인 기능 자체가 되돌아간다.
     */
    public boolean isNtsVerified() {
        return ntsOutcome().filter(NTS_VERIFIED_OUTCOMES::contains).isPresent();
    }

    /**
     * 사업자등록 진위확인 상태를 VERIFIED 로 올린다 — <b>인가 플래그 전이만</b> 한다.
     *
     * <p>⚠️ <b>이 메서드는 provenance({@code ocr_raw.ntsOutcome})를 건드리지 않는다. 의도된 것이다.</b>
     * 가입 경로({@code CompanyAccountWriter#createAccount})는 진위확인 결과와 <b>무관하게</b> 이 메서드를
     * 호출하므로(#1324 자동승인), 여기서 {@code ntsOutcome=VERIFIED} 를 찍으면 국세청 장애로 통과한
     * 회사(SKIPPED)에까지 "국세청이 확인해 줌"이라는 <b>허위 provenance</b>가 박힌다 — 배지
     * ({@link #isNtsVerified()})와 재검증 대상 판정
     * ({@code CompanyRepository#findNtsReverifyTargets})이 동시에 무너진다.
     *
     * <p><b>가입 경로 provenance 의 진실 소스 = {@code CompanySignupService.buildOcrRaw(verification)}</b>
     * 다. 회사 생성 시점에 실제 outcome(VERIFIED/SKIPPED)이 {@code ocr_raw} 로 들어오고, 이 메서드는 그
     * 값을 <b>덮지 않는다</b>(중복 기록 금지 — 한 곳에서만 쓴다).
     *
     * <p>국세청이 <b>실제로 확인해 준</b> 결과로 VERIFIED 를 확정하는 경로(#888 재검증 배치)는 이 메서드가
     * 아니라 {@link #markBusinessVerifiedByNts()} 를 써야 한다.
     */
    public void markBusinessVerified() {
        this.verificationStatus = BusinessVerificationStatus.VERIFIED;
        this.verifiedAt = Instant.now();
    }

    /**
     * 국세청 재조회가 <b>실제로 진위를 확인해 준</b> VERIFIED 확정(#888 재검증 배치 전용, #1324 P1) —
     * 인가 플래그 전이({@link #markBusinessVerified()})에 더해 <b>provenance 를 함께 기록</b>한다.
     *
     * <p><b>왜 provenance 갱신이 필수인가</b> — 둘 다 이걸 빼면 즉시 깨진다:
     * <ul>
     *   <li><b>루프 종료</b>: 재검증 대상 조회({@code CompanyRepository#findNtsReverifyTargets})는
     *       "VERIFIED 인데 provenance 로 증명 불가"인 회사를 매 회차 다시 집는다. 확인에 성공하고도
     *       {@code ntsOutcome} 이 SKIPPED 로 남으면 <b>같은 회사를 매일 재조회하는 무한 루프</b>가 되어
     *       국세청 일일 쿼터를 잠식한다.</li>
     *   <li><b>배지 정합</b>: {@link #isNtsVerified()} 는 provenance 로만 판정하므로, 갱신하지 않으면
     *       진짜 검증을 통과한 회사의 "사업자 인증 완료" 배지가 거짓으로 꺼진 채 남는다.</li>
     * </ul>
     *
     * <p>키 규약은 가입 경로({@code CompanySignupService.buildOcrRaw})와 <b>동일</b>하다:
     * {@code ntsOutcome=VERIFIED} + {@code ntsCheckedAt}(실제 조회 시각). 기존 키({@code source} 등)는
     * {@link JsonValidator#mergeTextFields} 로 <b>병합 보존</b>한다 — 통째 교체는 감사 기록 소실이다
     * (클래스 javadoc 경고).
     */
    public void markBusinessVerifiedByNts() {
        markBusinessVerified();
        this.businessRegistrationOcrRaw = JsonValidator.mergeTextFields(
                this.businessRegistrationOcrRaw,
                Map.of(NTS_OUTCOME_FIELD, NTS_OUTCOME_VERIFIED,
                        NTS_CHECKED_AT_FIELD, Instant.now().toString()));
    }

    /**
     * 사업자등록 진위확인 실패 확정(#888 PENDING 자동 재검증 스케줄러 전용) — 국세청이 미등록/불일치/
     * 휴업/폐업을 확정 응답했을 때 호출한다. 국세청 장애·미설정으로 인한 SKIPPED(fail-open)는 이 메서드를
     * 호출하지 않고 PENDING을 유지해야 한다(호출부 책임 — 장애를 실패로 오판하면 안 된다).
     *
     * <p>⚠️ {@code verifiedAt} 처리 방침: 갱신하지 않는다(null 유지). 이 필드명은 "진위 확인 완료 시각"이
     * 아니라 {@link #markBusinessVerified()}가 뜻하는 "검증 성공(VERIFIED) 시각"으로 좁게 쓰인다 — 실패
     * 확정 시각이 필요해지면 이 필드를 재사용하지 말고 {@code failedAt} 같은 전용 컬럼을 새로 추가할 것
     * (재사용 시 이후 재검증으로 FAILED→VERIFIED 전이가 생기면 "최초 검증 시각"과 "최근 실패 시각"이
     * 뒤섞여 의미가 오염된다).
     *
     * <p><b>이 한 줄이 회사 스코프를 닫는다(#1324 리뷰 확인)</b>: 스코프 판정
     * ({@code CompanyMembershipRepository.existsEffectiveApprovedMembership})과 동일 불변식의 DB 트리거
     * ({@code check_inspection_assigned_inspector_company})가 <b>둘 다</b>
     * {@code verificationStatus=VERIFIED} 를 요구하므로, FAILED 전이만으로 오너를 포함한 <b>전 구성원</b>의
     * 점검 생성·담당자 배정이 막힌다. 따라서 호출부가 {@link CompanyMembership} 을 추가로 회수할 필요는
     * <b>없다</b> — 회수는 차단에 아무것도 더하지 않으면서 되돌릴 수 없는 상태만 만든다(근거는
     * {@code PendingBusinessReverifyWriter#markFailed} javadoc 참고).
     *
     * <p>이 배치 강등을 사람이 되돌리는 경로는 {@link #restoreBusinessVerificationByAdmin(String)} 이다
     * (#1367). 사람 판단으로 <b>거는</b> 킬스위치는 {@link #revokeBusinessVerificationByAdmin(String)} 이며,
     * 이 메서드와 달리 provenance 를 함께 남긴다 — 이 메서드는 배치 전용이라 provenance 를 건드리지 않고
     * 사유는 호출부(writer)의 경고 로그가 담당한다.
     */
    public void markBusinessVerificationFailed() {
        this.verificationStatus = BusinessVerificationStatus.FAILED;
    }

    /**
     * <b>플랫폼 관리자 킬스위치</b>(#1367) — 사람 판단으로 회사 검증을 무효화해 회사 스코프를 즉시 닫는다.
     *
     * <p><b>왜 필요한가</b>: #1324 자동승인은 진위확인 결과와 무관하게 회사 스코프를 연다. 사칭·오등록이
     * 발견돼도 그것을 되돌리는 앱 경로가 {@link #markBusinessVerificationFailed()}(재검증 배치 전용)뿐이라
     * 운영은 수동 SQL 말고는 손쓸 방법이 없었다. 이 메서드가 그 수동 SQL 을 앱 경로로 대체한다.
     *
     * <p><b>provenance 처리</b>(모두 {@link JsonValidator#mergeTextFields} 병합 — 통째 교체는 감사 기록
     * 소실이다, 클래스 javadoc 경고):
     * <ul>
     *   <li>{@code ntsOutcome = ADMIN_REVOKED} — <b>반드시 덮는다.</b> 화이트리스트 밖 값이라
     *       {@link #isNtsVerified()} 가 false 가 되어 "사업자 인증 완료" 배지가 함께 꺼진다. 이 키를 두면
     *       차단된 회사에 인증 배지가 켜진 채 남는 부정합이 생긴다.</li>
     *   <li>{@code ntsOutcomeBeforeRevoke} — 덮기 직전 값을 보존한다(없으면 키를 쓰지 않는다).
     *       무효화가 오판이었는지 사후에 판단하려면 원래 판정이 남아 있어야 한다.</li>
     *   <li>{@code adminRevokedAt} · {@code adminRevokeReason} — 조치 시각과 사유.</li>
     * </ul>
     *
     * <p><b>멤버십은 회수하지 않는다</b> — {@code verificationStatus ≠ VERIFIED} 한 줄로 스코프 판정
     * ({@code CompanyMembershipRepository.existsEffectiveApprovedMembership})과 DB 트리거
     * ({@code check_inspection_assigned_inspector_company})가 둘 다 닫히므로 회수는 차단에 아무것도 더하지
     * 않으면서 되돌릴 수 없는 상태만 만든다({@code PendingBusinessReverifyWriter#markFailed} javadoc 의
     * 3근거). 넣으면 {@link #restoreBusinessVerificationByAdmin(String)} 이 반쪽이 된다.
     *
     * <p>{@code verifiedAt} 은 건드리지 않는다({@link #markBusinessVerificationFailed()} 방침 유지).
     *
     * <p><b>멱등 no-op 이 아니다</b>: 이미 {@code FAILED} 면 예외를 던진다. 두 번째 호출을 조용히 통과시키면
     * 최초 사유·{@code ntsOutcomeBeforeRevoke} 가 덮여 감사 기록이 흐려진다.
     *
     * @param reason       무효화 사유(감사 기록의 유일한 근거 — 호출부가 필수값으로 검증한다)
     * @param actorUserId  조치한 플랫폼 관리자 식별자(provenance 에 <b>id 만</b> 남긴다 — 이메일·이름 금지)
     */
    public void revokeBusinessVerificationByAdmin(String reason, Long actorUserId) {
        if (this.verificationStatus == BusinessVerificationStatus.FAILED) {
            throw new DomainStateTransitionException(
                    "검증 무효화 불가: 이미 무효화된 회사다(현재 verificationStatus=FAILED)");
        }
        Map<String, String> provenance = new LinkedHashMap<>();
        provenance.put(NTS_OUTCOME_FIELD, NTS_OUTCOME_ADMIN_REVOKED);
        // 직전 값이 없으면(키 부재·파손 JSON) null 이며, mergeTextFields 가 null 항목을 무시한다.
        provenance.put(NTS_OUTCOME_BEFORE_REVOKE_FIELD, ntsOutcome().orElse(null));
        provenance.put(ADMIN_REVOKED_AT_FIELD, Instant.now().toString());
        provenance.put(ADMIN_REVOKE_REASON_FIELD, reason);
        provenance.put(ADMIN_REVOKED_BY_FIELD, actorUserId == null ? null : actorUserId.toString());
        this.businessRegistrationOcrRaw =
                JsonValidator.mergeTextFields(this.businessRegistrationOcrRaw, provenance);
        this.verificationStatus = BusinessVerificationStatus.FAILED;
    }

    /**
     * <b>플랫폼 관리자 override</b>(#1367 P1-A) — 국세청 판정과 무관하게 회사 스코프를 여는 명시적 조치다.
     *
     * <p><b>왜 필요한가</b>: 대표자 변경으로 {@code MISMATCH} 가 나는 회사는 국세청이 계속 MISMATCH 를
     * 응답하고(엔티티에 대표자명 수정 경로가 없다), 새 정책상 MISMATCH 는 자동 강등도 자동 승격도 하지
     * 않는다. 그런 회사를 {@link #restoreBusinessVerificationByAdmin} 로 PENDING 에 돌려놓으면 <b>PENDING 에
     * 영구 고착</b>돼 스코프가 영영 열리지 않는다 — 이 PR 이 없애려던 수동 SQL 로 되돌아간다. 그 경우
     * 사람이 실물 확인 후 여는 경로가 이 메서드다.
     *
     * <p><b>안전장치 = 자동 재차단</b>: {@code ntsOutcome = ADMIN_OVERRIDE_VERIFIED} 는 인정 화이트리스트
     * <b>밖</b>이라 ①"사업자 인증 완료" 배지는 <b>꺼진 채 유지</b>되고(국세청이 확인해 준 게 아니다)
     * ②재검증 대상에 <b>계속 남는다</b> → 국세청이 나중에 미등록·폐업을 확정하면 배치가 자동으로 다시
     * 차단한다. {@link #NTS_OUTCOME_ADMIN_OVERRIDE} javadoc 참고.
     *
     * <p>⚠️ <b>안전장치가 없는 경우</b>: 개업일자가 없거나 데모 시드 회사면 재검증 대상 조회에 잡히지
     * 않거나(개업일자) 국세청 호출 전에 스킵되므로(데모) <b>자동 재차단이 동작하지 않는다</b>. override 는
     * 그래도 허용하되(배치에 의존하는 조치가 아니다) 호출부가 그 사실을 경고 로그로 남긴다.
     *
     * <p>{@code verifiedAt} 은 건드리지 않는다 — 국세청 검증 성공 시각이 아니기 때문이다.
     *
     * @param reason      개방 사유(감사 기록)
     * @param actorUserId 조치한 플랫폼 관리자 식별자
     */
    public void overrideBusinessVerificationByAdmin(String reason, Long actorUserId) {
        if (this.verificationStatus == BusinessVerificationStatus.VERIFIED) {
            throw new DomainStateTransitionException(
                    "검증 강제 개방 불가: 이미 회사 스코프가 열려 있다(현재 verificationStatus=VERIFIED)");
        }
        Map<String, String> provenance = new LinkedHashMap<>();
        provenance.put(NTS_OUTCOME_FIELD, NTS_OUTCOME_ADMIN_OVERRIDE);
        provenance.put(NTS_OUTCOME_BEFORE_OVERRIDE_FIELD, ntsOutcome().orElse(null));
        provenance.put(ADMIN_OVERRIDDEN_AT_FIELD, Instant.now().toString());
        provenance.put(ADMIN_OVERRIDE_REASON_FIELD, reason);
        provenance.put(ADMIN_OVERRIDDEN_BY_FIELD, actorUserId == null ? null : actorUserId.toString());
        this.businessRegistrationOcrRaw =
                JsonValidator.mergeTextFields(this.businessRegistrationOcrRaw, provenance);
        this.verificationStatus = BusinessVerificationStatus.VERIFIED;
    }

    /**
     * <b>플랫폼 관리자 복구</b>(#1367) — 무효화(또는 배치 강등)된 회사를 되돌린다. <b>두 경로로 갈린다</b>
     * ({@link AdminRestoreMode}):
     *
     * <ol>
     *   <li><b>{@link AdminRestoreMode#RESTORED_TO_VERIFIED}</b> — 이 무효화가 <b>관리자 자신의 조치</b>
     *       ({@code ntsOutcome = ADMIN_REVOKED})이고 그 <b>직전 상태가 국세청 인정</b>
     *       ({@code ntsOutcomeBeforeRevoke ∈ VERIFIED/LEGACY_VERIFIED})이었다면, 그 상태로 되돌린다
     *       ({@code ntsOutcome} 도 직전 값으로 원복). <b>국세청 판정을 덮는 게 아니라 관리자 자기 조치의
     *       순수 취소</b>이므로 "관리자는 국세청 판정을 대신하지 않는다"는 원칙과 충돌하지 않는다.
     *       <p>PENDING 을 거치면 다음 배치 회차(하루 1회)까지 스코프가 닫힌 채라, 오조작 revoke 한 건이
     *       정상 회사를 최대 하루 가까이 서비스 중단시킨다 — 그것을 막는 것이 이 분기의 존재 이유다.</li>
     *   <li><b>{@link AdminRestoreMode#RESTORED_TO_PENDING}</b> — 그 밖(배치가 강등한 FAILED,
     *       무효화 직전이 SKIPPED·UNKNOWN_BACKFILL 이던 회사 등)은 {@code PENDING} 으로만 되돌린다.
     *       스코프는 아직 닫힌 채이고, PENDING 은 {@code CompanyRepository#findNtsReverifyTargets} 의 첫
     *       갈래라 <b>다음 배치가 국세청에 다시 물어 재판정</b>한다.
     *       <p>⚠️ 이 경로는 배치에 의존하므로 호출부가 <b>"배치가 실제로 집을 수 있는 회사인가"</b>를
     *       먼저 확인해야 한다(개업일자 존재 · 데모 시드 아님 · 반려 아님). 그러지 않으면 PENDING 에
     *       영구 고착돼 <b>"복구했다"고 착각한 채 스코프가 영구 폐쇄</b>된다.</li>
     * </ol>
     *
     * <p>{@code verifiedAt} 은 두 경로 모두 건드리지 않는다(무효화와 동일 방침).
     *
     * @param reason      복구 사유(감사 기록)
     * @param actorUserId 조치한 플랫폼 관리자 식별자
     * @return 어느 경로로 복원했는지 — 호출부가 로그·응답에 구분해 남긴다
     */
    public AdminRestoreMode restoreBusinessVerificationByAdmin(String reason, Long actorUserId) {
        if (this.verificationStatus != BusinessVerificationStatus.FAILED) {
            throw new DomainStateTransitionException(
                    "검증 복구 불가: 무효화(FAILED) 상태의 회사만 복구할 수 있다(현재 verificationStatus=%s)"
                            .formatted(this.verificationStatus));
        }
        boolean undoAdminRevoke = isAdminRevokeUndoable();

        Map<String, String> provenance = new LinkedHashMap<>();
        provenance.put(NTS_OUTCOME_FIELD, undoAdminRevoke
                ? outcomeBeforeRevoke().orElseThrow()   // isAdminRevokeUndoable 이 존재를 이미 보장한다
                : NTS_OUTCOME_ADMIN_RESTORED);
        provenance.put(ADMIN_RESTORED_AT_FIELD, Instant.now().toString());
        provenance.put(ADMIN_RESTORE_REASON_FIELD, reason);
        provenance.put(ADMIN_RESTORED_BY_FIELD, actorUserId == null ? null : actorUserId.toString());
        this.businessRegistrationOcrRaw =
                JsonValidator.mergeTextFields(this.businessRegistrationOcrRaw, provenance);

        this.verificationStatus = undoAdminRevoke
                ? BusinessVerificationStatus.VERIFIED
                : BusinessVerificationStatus.PENDING;
        return undoAdminRevoke ? AdminRestoreMode.RESTORED_TO_VERIFIED : AdminRestoreMode.RESTORED_TO_PENDING;
    }

    /**
     * 이 회사의 복구가 <b>관리자 자기 조치의 순수 취소</b>(→ VERIFIED 즉시 복원)에 해당하는지 —
     * 호출부가 <b>전이 전에</b> 어느 분기가 될지 알아야 PENDING 경로 전용 가드(개업일자·데모·반려)를
     * 적용할지 판단할 수 있어 공개한다.
     */
    public boolean isAdminRevokeUndoable() {
        return this.verificationStatus == BusinessVerificationStatus.FAILED
                && isAdminRevoked()
                && outcomeBeforeRevoke().filter(NTS_VERIFIED_OUTCOMES::contains).isPresent();
    }

    /** 관리자 킬스위치로 무효화된 상태인가({@code FAILED} ∧ {@code ntsOutcome = ADMIN_REVOKED}). */
    public boolean isAdminRevoked() {
        return this.verificationStatus == BusinessVerificationStatus.FAILED
                && ntsOutcome().filter(NTS_OUTCOME_ADMIN_REVOKED::equals).isPresent();
    }

    /** 관리자 override 로 열린 상태인가({@code ntsOutcome = ADMIN_OVERRIDE_VERIFIED}). */
    public boolean isAdminOverridden() {
        return ntsOutcome().filter(NTS_OUTCOME_ADMIN_OVERRIDE::equals).isPresent();
    }

    private Optional<String> outcomeBeforeRevoke() {
        return JsonValidator.readTextField(this.businessRegistrationOcrRaw, NTS_OUTCOME_BEFORE_REVOKE_FIELD);
    }

    /**
     * 재검증 배치가 이 회사를 처리 시도했음을 스탬프한다(#1367 P1-C) — 대기열 라운드로빈의 정렬 축.
     *
     * <p>⚠️ {@code ntsOutcome} 은 <b>건드리지 않는다</b>. 그 키는 재검증 <b>대상 집합</b>(where 절)을
     * 결정하므로, 스탬프가 그것을 바꾸면 "정렬 개선"이 아니라 자동 통제 자체를 파괴한다.
     */
    public void stampNtsReverifyAttempt() {
        this.businessRegistrationOcrRaw = JsonValidator.mergeTextFields(
                this.businessRegistrationOcrRaw,
                Map.of(NTS_LAST_ATTEMPT_AT_FIELD, Instant.now().toString()));
    }

    /**
     * 자동 강등하지 않는 경보 판정(MISMATCH/SUSPENDED)을 기록한다(#1367 P2-3) — 시도 스탬프도 함께 찍는다.
     *
     * <p>{@code ntsOutcome} 은 건드리지 않는다(위와 동일 이유 + 강등하지 않기로 한 판정을 인가 근거 키에
     * 쓰면 안 된다). 이 값이 있어야 진단 API 가 "어제 어떤 경보를 받았는지"를 관리자에게 보여줄 수 있다.
     *
     * @param outcomeLabel 국세청 판정 라벨(문자열 — 값 공간이 enum 이 아니고, 엔티티가 bizverify 패키지를
     *                     역참조하지 않기 위해 라벨로 받는다)
     */
    public void stampNtsReverifyAlert(String outcomeLabel) {
        String now = Instant.now().toString();
        Map<String, String> provenance = new LinkedHashMap<>();
        provenance.put(NTS_LAST_ATTEMPT_AT_FIELD, now);
        provenance.put(NTS_LAST_ALERT_OUTCOME_FIELD, outcomeLabel);
        provenance.put(NTS_LAST_ALERT_AT_FIELD, now);
        this.businessRegistrationOcrRaw =
                JsonValidator.mergeTextFields(this.businessRegistrationOcrRaw, provenance);
    }

    /** 재검증 배치의 마지막 처리 시도 시각({@code ocr_raw.ntsLastAttemptAt}) — 없으면 empty. */
    public Optional<String> ntsLastAttemptAt() {
        return JsonValidator.readTextField(this.businessRegistrationOcrRaw, NTS_LAST_ATTEMPT_AT_FIELD);
    }

    /** 마지막 경보 판정 라벨({@code ocr_raw.ntsLastAlertOutcome}) — 없으면 empty. */
    public Optional<String> ntsLastAlertOutcome() {
        return JsonValidator.readTextField(this.businessRegistrationOcrRaw, NTS_LAST_ALERT_OUTCOME_FIELD);
    }

    /** 마지막 경보 시각({@code ocr_raw.ntsLastAlertAt}) — 없으면 empty. */
    public Optional<String> ntsLastAlertAt() {
        return JsonValidator.readTextField(this.businessRegistrationOcrRaw, NTS_LAST_ALERT_AT_FIELD);
    }

    /**
     * 국세청 진위확인 provenance 라벨({@code ocr_raw.ntsOutcome}) — 값이 없거나 JSON 이 깨졌으면 empty.
     *
     * <p>키 이름을 이 클래스 밖으로 흘리지 않기 위한 읽기 전용 접근자다(플랫폼 관리자 진단 응답 ·
     * 무효화 직전 값 보존이 쓴다). ⚠️ 값 공간은 enum 이 아니다 — 클래스 javadoc 참고.
     */
    public Optional<String> ntsOutcome() {
        return JsonValidator.readTextField(this.businessRegistrationOcrRaw, NTS_OUTCOME_FIELD);
    }

    /**
     * 실제 국세청 조회 시각({@code ocr_raw.ntsCheckedAt}) — 없으면 empty.
     * V38 소급 스탬프({@code ntsBackfilledAt})는 출처가 달라 <b>이 키에 담기지 않는다</b>(클래스 javadoc).
     */
    public Optional<String> ntsCheckedAt() {
        return JsonValidator.readTextField(this.businessRegistrationOcrRaw, NTS_CHECKED_AT_FIELD);
    }

    private void requirePendingReview(String action) {
        if (this.status != CompanyStatus.PENDING_REVIEW) {
            throw new DomainStateTransitionException(
                    "%s 불가: 현재 회사 상태=%s, 심사 대기 상태에서만 처리할 수 있다"
                            .formatted(action, this.status));
        }
    }
}
