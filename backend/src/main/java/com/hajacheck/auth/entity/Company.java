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
import java.util.Map;
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
        return JsonValidator.readTextField(this.businessRegistrationOcrRaw, NTS_OUTCOME_FIELD)
                .filter(NTS_VERIFIED_OUTCOMES::contains)
                .isPresent();
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
     * <b>없다</b> — 회수는 차단에 아무것도 더하지 않으면서 되돌릴 수 없는 상태만 만든다(근거·후속 #1367 은
     * {@code PendingBusinessReverifyWriter#markFailed} javadoc 참고).
     */
    public void markBusinessVerificationFailed() {
        this.verificationStatus = BusinessVerificationStatus.FAILED;
    }

    private void requirePendingReview(String action) {
        if (this.status != CompanyStatus.PENDING_REVIEW) {
            throw new DomainStateTransitionException(
                    "%s 불가: 현재 회사 상태=%s, 심사 대기 상태에서만 처리할 수 있다"
                            .formatted(action, this.status));
        }
    }
}
