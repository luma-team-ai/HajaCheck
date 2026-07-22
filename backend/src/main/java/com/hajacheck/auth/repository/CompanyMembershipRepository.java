package com.hajacheck.auth.repository;

import com.hajacheck.auth.entity.BusinessVerificationStatus;
import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.CompanyMembershipStatus;
import com.hajacheck.auth.entity.CompanyStatus;
import com.hajacheck.auth.entity.UserStatus;
import java.time.Instant;
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
}
