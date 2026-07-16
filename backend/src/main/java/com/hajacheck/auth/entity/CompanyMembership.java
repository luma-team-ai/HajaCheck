package com.hajacheck.auth.entity;

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

    public void approve() {
        requireStatus("approve", CompanyMembershipStatus.PENDING);
        this.status = CompanyMembershipStatus.APPROVED;
        this.approvedAt = Instant.now();
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
        throw new IllegalStateException(
                "%s 불가: 현재 상태=%s, 허용 상태=%s".formatted(action, this.status, Arrays.toString(allowed)));
    }

    public boolean isEffectiveAt(Instant now) {
        return this.status == CompanyMembershipStatus.APPROVED
                && this.approvedAt != null
                && this.revokedAt == null
                && (this.expiresAt == null || this.expiresAt.isAfter(now));
    }
}
