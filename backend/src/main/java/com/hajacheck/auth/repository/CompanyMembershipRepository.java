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
     * <b>회사 검증 조치가 영향을 주는(또는 이미 준) 구성원 수</b>(#1367 P2-5) — 플랫폼 관리자 진단 응답이
     * "이 무효화가 몇 명의 작업을 멈추는가"를 보여주는 데 쓴다.
     *
     * <p><b>왜 {@code users.company_id + status=ACTIVE} 카운트로는 안 되는가</b>: 실제로 막히는 대상은
     * {@link #existsEffectiveApprovedMembership} 을 만족하는 사용자다. 단순 사용자 카운트는 초대 대기
     * (PENDING)·회수된(REVOKED)·만료된 멤버십 사용자까지 세어 <b>영향 범위를 과대 보고</b>하고, 그 숫자로
     * 관리자가 "이렇게 많은 사람이 멈추는데 정말 막을까"를 판단하면 왜곡된 결정을 하게 된다.
     *
     * <p><b>⚠️ 회사 검증 상태({@code verificationStatus = VERIFIED}) 조건만은 일부러 뺐다</b> —
     * 그 조건을 넣으면 이미 무효화된(FAILED) 회사에서 카운트가 항상 0 이 되어, 무효화 <b>직후</b> 응답과
     * 사후 진단 조회가 "영향 0명"이라는 무의미한 값을 준다. 이 카운트가 답해야 하는 질문은 "지금 스코프가
     * 열려 있는가"가 아니라 <b>"이 회사의 유효 구성원이 몇 명인가(= 이 조치의 영향 범위)"</b>다.
     * 나머지 조건(유효 APPROVED 멤버십 · 사용자 ACTIVE · {@code users.company_id} 일치 · 회사 APPROVED)은
     * {@link #existsEffectiveApprovedMembership} 과 동일하게 유지한다.
     */
    default long countEffectiveApprovedMembers(Long companyId, Instant now) {
        return countEffectiveApprovedMembers(
                companyId,
                now,
                CompanyMembershipStatus.APPROVED,
                UserStatus.ACTIVE,
                CompanyStatus.APPROVED);
    }

    @Query("""
            select count(m)
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
              and c.status = :companyStatus
            """)
    long countEffectiveApprovedMembers(
            @Param("companyId") Long companyId,
            @Param("now") Instant now,
            @Param("membershipStatus") CompanyMembershipStatus membershipStatus,
            @Param("userStatus") UserStatus userStatus,
            @Param("companyStatus") CompanyStatus companyStatus);

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
