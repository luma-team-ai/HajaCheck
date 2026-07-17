package com.hajacheck.auth.repository;

import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.CompanyMembershipStatus;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CompanyMembershipRepository extends JpaRepository<CompanyMembership, Long> {

    @Query("""
            select (count(m) > 0)
            from CompanyMembership m
            where m.companyId = :companyId
              and m.userId = :userId
              and m.status = :status
              and m.approvedAt is not null
              and m.revokedAt is null
              and (m.expiresAt is null or m.expiresAt > :now)
            """)
    boolean existsEffectiveMembership(
            @Param("companyId") Long companyId,
            @Param("userId") Long userId,
            @Param("status") CompanyMembershipStatus status,
            @Param("now") Instant now);
}
