package com.hajacheck.auth.repository;

import com.hajacheck.auth.entity.CompanyMembership;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CompanyMembershipRepository extends JpaRepository<CompanyMembership, Long> {

    Optional<CompanyMembership> findByCompanyIdAndUserId(Long companyId, Long userId);

    @Query("""
            select (count(m) > 0)
            from CompanyMembership m
            join m.user u
            join m.company c
            where m.companyId = :companyId
              and m.userId = :userId
              and m.status = com.hajacheck.auth.entity.CompanyMembershipStatus.APPROVED
              and m.approvedAt is not null
              and m.revokedAt is null
              and (m.expiresAt is null or m.expiresAt > :now)
              and u.status = com.hajacheck.auth.entity.UserStatus.ACTIVE
              and u.companyId = m.companyId
              and c.status = com.hajacheck.auth.entity.CompanyStatus.APPROVED
              and c.verificationStatus = com.hajacheck.auth.entity.BusinessVerificationStatus.VERIFIED
            """)
    boolean existsEffectiveApprovedMembership(
            @Param("companyId") Long companyId,
            @Param("userId") Long userId,
            @Param("now") Instant now);
}
