package com.hajacheck.auth.entity;

import com.hajacheck.global.common.BaseTimeEntity;
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
 * <p>OCR: 현재 stub(수동입력). {@code businessRegistrationOcrRaw} 는 jsonb 원본(감사·재처리용)이며
 * 가입 시점엔 {@code {"source":"MANUAL_INPUT"}} 를 저장한다.
 */
@Entity
@Getter
@Table(name = "companies")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Company extends BaseTimeEntity {

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
                    String representativeName, String address, String addressDetail,
                    String businessRegistrationFileUrl, String businessRegistrationOcrRaw,
                    BusinessVerificationStatus verificationStatus, CompanyStatus status) {
        this.ownerUserId = ownerUserId;
        this.name = name;
        this.businessRegistrationNumber = businessRegistrationNumber;
        this.representativeName = representativeName;
        this.address = address;
        this.addressDetail = addressDetail;
        this.businessRegistrationFileUrl = businessRegistrationFileUrl;
        this.businessRegistrationOcrRaw = businessRegistrationOcrRaw;
        this.verificationStatus = verificationStatus == null ? BusinessVerificationStatus.PENDING : verificationStatus;
        this.status = status == null ? CompanyStatus.PENDING_REVIEW : status;
    }

    /**
     * 가입 신청 팩토리 — 진위확인 PENDING, 승인 PENDING_REVIEW 로 생성.
     * OCR 은 stub 값(호출부에서 {@code {"source":"MANUAL_INPUT"}} 전달).
     */
    public static Company createPendingReview(Long ownerUserId, String name, String businessRegistrationNumber,
                                              String representativeName, String address, String addressDetail,
                                              String businessRegistrationFileUrl, String businessRegistrationOcrRaw) {
        String normalizedOcrRaw = JsonValidator.normalizeOrRequireValid(
                businessRegistrationOcrRaw, "OCR 원본(businessRegistrationOcrRaw)");
        return Company.builder()
                .ownerUserId(ownerUserId)
                .name(name)
                .businessRegistrationNumber(businessRegistrationNumber)
                .representativeName(representativeName)
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
            throw new IllegalStateException(
                    "approve 불가: 사업자등록정보 검증이 완료된 회사만 승인할 수 있다");
        }
        this.status = CompanyStatus.APPROVED;
        this.reviewedBy = reviewerUserId;
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
     * 사업자등록 진위확인 완료 (상태 전이 — 현재 미배선, 실제 OCR/국세청 연동 후속 과제).
     */
    public void markBusinessVerified() {
        this.verificationStatus = BusinessVerificationStatus.VERIFIED;
        this.verifiedAt = Instant.now();
    }

    private void requirePendingReview(String action) {
        if (this.status != CompanyStatus.PENDING_REVIEW) {
            throw new IllegalStateException(
                    "%s 불가: 현재 회사 상태=%s, 심사 대기 상태에서만 처리할 수 있다"
                            .formatted(action, this.status));
        }
    }
}
