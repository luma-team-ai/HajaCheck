package com.hajacheck.auth.repository;

import com.hajacheck.auth.entity.BusinessVerificationStatus;
import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.CompanyMembershipStatus;
import com.hajacheck.auth.entity.CompanyStatus;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CompanyMembershipRepository extends JpaRepository<CompanyMembership, Long> {

    Optional<CompanyMembership> findByCompanyIdAndUserId(Long companyId, Long userId);

    default boolean existsEffectiveApprovedMembership(Long companyId, Long userId, Instant now) {
        return existsEffectiveApprovedMembership(
                companyId,
                userId,
                now,
                CompanyMembershipStatus.APPROVED,
                UserStatus.ACTIVE,
                CompanyStatus.APPROVED,
                BusinessVerificationStatus.VERIFIED);
    }

    /**
     * 배정 가능한 회사 소속 사용자 목록(#690) — validateAssignableInspector 와 동일한 자격 조건
     * (활성·INSPECTOR/ADMIN 역할·유효 APPROVED 멤버십)을 단건 검증 대신 목록 조회로 확장한 것.
     */
    default List<User> findAssignableUsersInCompany(Long companyId, Instant now) {
        return findAssignableUsersInCompany(
                companyId,
                now,
                CompanyMembershipStatus.APPROVED,
                UserStatus.ACTIVE,
                CompanyStatus.APPROVED,
                BusinessVerificationStatus.VERIFIED,
                List.of(Role.INSPECTOR, Role.ADMIN));
    }

    @Query("""
            select (count(m) > 0)
            from CompanyMembership m
            join m.user u
            join m.company c
            where m.companyId = :companyId
              and m.userId = :userId
              and m.status = :membershipStatus
              and m.approvedAt is not null
              and m.revokedAt is null
              and (m.expiresAt is null or m.expiresAt > :now)
              and u.status = :userStatus
              and u.companyId = m.companyId
              and c.status = :companyStatus
              and c.verificationStatus = :verificationStatus
            """)
    boolean existsEffectiveApprovedMembership(
            @Param("companyId") Long companyId,
            @Param("userId") Long userId,
            @Param("now") Instant now,
            @Param("membershipStatus") CompanyMembershipStatus membershipStatus,
            @Param("userStatus") UserStatus userStatus,
            @Param("companyStatus") CompanyStatus companyStatus,
            @Param("verificationStatus") BusinessVerificationStatus verificationStatus);

    @Query("""
            select u
            from CompanyMembership m
            join m.user u
            join m.company c
            where m.companyId = :companyId
              and m.status = :membershipStatus
              and m.approvedAt is not null
              and m.revokedAt is null
              and (m.expiresAt is null or m.expiresAt > :now)
              and u.status = :userStatus
              and u.companyId = m.companyId
              and u.role in :roles
              and c.status = :companyStatus
              and c.verificationStatus = :verificationStatus
            order by u.id asc
            """)
    List<User> findAssignableUsersInCompany(
            @Param("companyId") Long companyId,
            @Param("now") Instant now,
            @Param("membershipStatus") CompanyMembershipStatus membershipStatus,
            @Param("userStatus") UserStatus userStatus,
            @Param("companyStatus") CompanyStatus companyStatus,
            @Param("verificationStatus") BusinessVerificationStatus verificationStatus,
            @Param("roles") List<Role> roles);
}
