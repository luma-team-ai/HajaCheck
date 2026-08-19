package com.hajacheck.auth.entity;

import com.hajacheck.global.common.BaseTimeEntity;
import com.hajacheck.global.exception.DomainStateTransitionException;
import com.hajacheck.global.exception.DomainValidationException;
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
import java.time.Instant;
import java.util.Arrays;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 기업 초대·소속 승인 이력과 현재 소속 판정의 기준이 되는 엔티티.
 *
 * <p>기존 auth 엔티티 규약에 따라 사용자와 기업 FK는 식별자 값으로 보유한다. 유효 멤버십 판정에는
 * 사용자·기업 상태와 {@code User.companyId} 교차 확인이 추가로 필요하므로 이 엔티티의 상태만으로
 * 권한을 부여해서는 안 된다.</p>
 *
 * <p>⚠️ 계약: {@code Company.status}(승인/반려)와 이 엔티티의 {@code status}(멤버십 유효성)는 독립된 두
 * 상태 머신이다(HAJA-25 P2 — 현재 둘 다 서비스 미배선). {@code Company.approve}/{@code Company.reject}가
 * 배선될 때는 오너의 멤버십도 같은 트랜잭션에서 함께 전이시켜야 두 상태가 어긋나지 않는다.</p>
 */
@Entity
@Getter
@Table(
        name = "company_memberships",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_company_memberships_company_user",
                columnNames = {"company_id", "user_id"}),
        indexes = {
                @Index(name = "idx_company_memberships_company_status", columnList = "company_id,status"),
                @Index(name = "idx_company_memberships_user_status", columnList = "user_id,status")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CompanyMembership extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", insertable = false, updatable = false)
    private Company company;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    private User user;

    @Column(name = "invited_by")
    private Long invitedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invited_by", insertable = false, updatable = false)
    private User inviter;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "company_membership_status_type", nullable = false)
    private CompanyMembershipStatus status;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Builder(access = AccessLevel.PRIVATE)
    private CompanyMembership(Long companyId, Long userId, Long invitedBy,
                              CompanyMembershipStatus status, Instant approvedAt,
                              Instant expiresAt, Instant revokedAt) {
        this.companyId = companyId;
        this.userId = userId;
        this.invitedBy = invitedBy;
        this.status = status == null ? CompanyMembershipStatus.PENDING : status;
        this.approvedAt = approvedAt;
        this.expiresAt = expiresAt;
        this.revokedAt = revokedAt;
    }

    public static CompanyMembership invite(Long companyId, Long userId, Long invitedBy, Instant expiresAt) {
        requireFutureExpiration(expiresAt, Instant.now());
        return CompanyMembership.builder()
                .companyId(companyId)
                .userId(userId)
                .invitedBy(invitedBy)
                .status(CompanyMembershipStatus.PENDING)
                .expiresAt(expiresAt)
                .build();
    }

    public static CompanyMembership approvedOwner(Long companyId, Long ownerUserId) {
        return CompanyMembership.builder()
                .companyId(companyId)
                .userId(ownerUserId)
                .status(CompanyMembershipStatus.APPROVED)
                .approvedAt(Instant.now())
                .build();
    }

    /**
     * 초대 코드 redeem(#1474) — 코드를 소비한 사용자를 해당 기업의 유효 멤버로 확정한다.
     *
     * <p>{@link #approvedOwner}와 별도 팩토리인 이유: 오너 팩토리를 재사용하면 <b>초대받은 사람이 회사
     * 오너로 취급</b>된다. 두 경로는 만들어내는 행의 의미가 다르므로 합치지 않는다.
     *
     * <p>{@link #invite}(PENDING) → {@link #approve} 2단계를 거치지 않고 곧바로 APPROVED 로 만드는 이유:
     * 초대 코드 방식은 승인 주체가 <b>코드를 발급한 관리자</b>이고 그 승인이 <b>코드 발급 시점에 이미
     * 일어났다</b>(발급 = 좌석 1석을 내주겠다는 의사표시, {@code QuotaService.hasAvailableSeat} 선검사).
     * redeem 은 그 승인을 수령하는 행위라 별도 승인 대기 단계가 존재하지 않는다.
     *
     * <p>{@code invitedBy} 는 null 이다 — 코드는 Redis 에 {@code companyId} 만 담고 발급자 식별자를
     * 보관하지 않아, 실제로 누가 발급했는지 알 수 없다. 모르는 값을 오너 id 등으로 임의로 채우지 않는다.
     *
     * <p>{@code expiresAt} 은 null(무기한) — {@link #approvedOwner}와 동일하다. 좌석 회수는 멤버십 만료가
     * 아니라 별도 경로({@link #revoke})의 책임이다.
     */
    public static CompanyMembership approvedMember(Long companyId, Long userId) {
        return approvedMember(companyId, userId, null);
    }

    /**
     * 관리자 콘솔 직접 등록(#1433) — 승인 주체를 알 수 있는 경로용 오버로드.
     *
     * <p>2-인자 {@link #approvedMember(Long, Long)}(초대 코드 redeem)와 달리 {@code invitedBy} 를 채운다:
     * 관리자 콘솔의 사용자 등록은 <b>요청한 관리자 자신이 곧 승인 주체</b>라 발급자가 명확하기 때문이다
     * (초대 코드는 Redis 에 companyId 만 담아 발급자를 알 수 없어 null 로 둔다 — 위 javadoc 참고).
     * 감사 추적상 "누가 이 소속을 만들었는가"를 남길 수 있는 경로에서는 남긴다.
     *
     * <p>나머지 의미는 2-인자 버전과 동일하다: {@link #invite}(PENDING) → {@link #approve} 2단계를 거치지
     * 않고 곧바로 APPROVED(관리자의 등록 행위 자체가 승인), {@code expiresAt} 은 null(무기한 — 좌석 회수는
     * {@link #revoke} 의 책임).
     */
    public static CompanyMembership approvedMember(Long companyId, Long userId, Long invitedBy) {
        return CompanyMembership.builder()
                .companyId(companyId)
                .userId(userId)
                .invitedBy(invitedBy)
                .status(CompanyMembershipStatus.APPROVED)
                .approvedAt(Instant.now())
                .build();
    }

    /**
     * 초대 코드 redeem 시 <b>이미 같은 (기업, 사용자) 행이 존재</b>할 때 그 행을 유효 멤버십으로 되돌린다
     * (#1474). 복합 유니크 제약 {@code uk_company_memberships_company_user} 때문에 새 행을 넣을 수 없어
     * 기존 행을 재사용한다 — {@link #reinvite}의 "재초대는 새 행을 추가하지 않는다" 원칙과 동일하다.
     *
     * <p>⚠️ 다른 전이 메서드와 달리 <b>이전 상태를 검사하지 않는다</b>. 근거: 이 메서드는
     * {@code InviteCodeService.redeem} 에서만 호출되고, 그 경로는 {@code User.requireWaiting()} 으로
     * <b>WAITING 사용자만</b> 통과시킨다. WAITING 은 정의상 어떤 회사 스코프도 갖지 않는 상태라
     * (소셜 가입 직후 또는 소속이 끊긴 상태), 이전 멤버십 상태가 무엇이든 "지금 이 코드로 이 회사에
     * 합류한다"가 최신 사실이다. 상태 검사를 넣으면 REVOKED·EXPIRED 이력이 있는 사용자가 정당한 새 초대를
     * 받고도 영구히 합류하지 못한다.
     *
     * <p>현재 {@link #revoke}·{@link #reinvite} 는 프로덕션 호출부가 0건이라 이 분기는 실질적으로
     * 도달하지 않는다. 그럼에도 두는 이유는 도달했을 때 유니크 제약 위반이 <b>500</b>으로 튀는 것을 막기
     * 위해서다(사용자에겐 원인을 알 수 없는 실패가 된다).
     */
    public void approveAsInvitedMember() {
        this.status = CompanyMembershipStatus.APPROVED;
        this.approvedAt = Instant.now();
        this.revokedAt = null;
        this.expiresAt = null;
    }

    public void approve() {
        requireStatus("approve", CompanyMembershipStatus.PENDING);
        Instant approvedAt = Instant.now();
        requireFutureExpiration(this.expiresAt, approvedAt);
        this.status = CompanyMembershipStatus.APPROVED;
        this.approvedAt = approvedAt;
        this.revokedAt = null;
    }

    public void reject() {
        requireStatus("reject", CompanyMembershipStatus.PENDING);
        this.status = CompanyMembershipStatus.REJECTED;
        this.approvedAt = null;
        this.revokedAt = null;
    }

    public void revoke() {
        requireStatus("revoke", CompanyMembershipStatus.APPROVED);
        this.status = CompanyMembershipStatus.REVOKED;
        this.revokedAt = Instant.now();
    }

    public void expire() {
        requireStatus("expire", CompanyMembershipStatus.PENDING, CompanyMembershipStatus.APPROVED);
        this.status = CompanyMembershipStatus.EXPIRED;
    }

    /**
     * 동일 기업·사용자 조합의 비활성 멤버십 행을 새 초대로 재사용한다.
     * 복합 유니크 제약을 유지하면서 재초대를 허용하기 위해 새 행을 추가하지 않는다.
     */
    public void reinvite(Long invitedBy, Instant expiresAt) {
        requireStatus("reinvite",
                CompanyMembershipStatus.REJECTED,
                CompanyMembershipStatus.REVOKED,
                CompanyMembershipStatus.EXPIRED);
        requireFutureExpiration(expiresAt, Instant.now());
        this.invitedBy = invitedBy;
        this.status = CompanyMembershipStatus.PENDING;
        this.approvedAt = null;
        this.expiresAt = expiresAt;
        this.revokedAt = null;
    }

    private void requireStatus(String action, CompanyMembershipStatus... allowed) {
        for (CompanyMembershipStatus candidate : allowed) {
            if (this.status == candidate) {
                return;
            }
        }
        throw new DomainStateTransitionException(
                "%s 불가: 현재 상태=%s, 허용 상태=%s".formatted(action, this.status, Arrays.toString(allowed)));
    }

    private static void requireFutureExpiration(Instant expiresAt, Instant reference) {
        if (expiresAt != null && !expiresAt.isAfter(reference)) {
            throw new DomainValidationException("멤버십 만료 시각은 현재보다 이후여야 한다");
        }
    }

    public boolean isEffectiveAt(Instant now) {
        return this.status == CompanyMembershipStatus.APPROVED
                && this.approvedAt != null
                && this.revokedAt == null
                && (this.expiresAt == null || this.expiresAt.isAfter(now));
    }
}
