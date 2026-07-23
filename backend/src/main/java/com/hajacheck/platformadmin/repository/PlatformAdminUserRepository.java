package com.hajacheck.platformadmin.repository;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.platformadmin.dto.PlatformAdminUserProjection;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 플랫폼 관리자 콘솔 — 사용자 관리(#576). AdminUserRepository(#405)와 쿼리 골격은 동일하되
 * companyId 스코프를 걷어내고 companyName을 함께 조회한다(전사 조회, PLATFORM_ADMIN 전용) —
 * 두 스코프를 같은 쿼리로 합치면 companyId 조건 하나만 빠뜨려도 인가 버그로 직결되므로
 * AdminUserRepository와 완전히 분리된 리포지토리로 둔다(설계 §6 원칙과 동일 이유).
 */
public interface PlatformAdminUserRepository extends JpaRepository<User, Long> {

    // PLATFORM_ADMIN 계정 자신은 이 목록에 나타나지 않는다(company_id 없음, 프론트 AdminUserRole =
    // Exclude<Role, 'PLATFORM_ADMIN'>과 동일 계약) — role != PLATFORM_ADMIN으로 항상 걸러낸다.
    // keyword는 이름·이메일뿐 아니라 기업명까지 매칭한다(PR #626 후속 요구사항, 개인 계정은 c.name이 null).
    @Query("""
            select new com.hajacheck.platformadmin.dto.PlatformAdminUserProjection(
                u.id, u.name, u.email, u.profileImageUrl, u.role, p.name, u.companyId, c.name,
                u.createdAt, u.lastLoginAt, u.status)
            from User u
            left join UserPlan up on up.userId = u.id and up.status = :activeStatus
            left join Plan p on p.id = up.planId
            left join Company c on c.id = u.companyId
            where u.role <> :platformAdminRole
              and (:keyword is null
                or lower(u.name) like :keyword escape '\\'
                or lower(u.email) like :keyword escape '\\'
                or lower(c.name) like :keyword escape '\\')
              and (:hasRole = false or u.role = :role)
              and (:hasStatus = false or u.status = :status)
              and (:hasPlan = false or p.name = :plan or (p.name is null and :planIsFree = true))
            order by u.createdAt desc
            """)
    Page<PlatformAdminUserProjection> search(
            @Param("keyword") String keyword,
            @Param("hasRole") boolean hasRole,
            @Param("role") Role role,
            @Param("hasStatus") boolean hasStatus,
            @Param("status") UserStatus status,
            @Param("hasPlan") boolean hasPlan,
            @Param("plan") PlanName plan,
            @Param("planIsFree") boolean planIsFree,
            @Param("activeStatus") UserPlanStatus activeStatus,
            @Param("platformAdminRole") Role platformAdminRole,
            Pageable pageable);

    long countByRoleNot(Role role);

    long countByStatusAndRoleNot(UserStatus status, Role role);

    long countByCreatedAtGreaterThanEqualAndCreatedAtLessThanAndRoleNot(
            LocalDateTime from, LocalDateTime to, Role role);

    // 회사별 마지막 ADMIN 보호(AdminUserRepository와 동일 목적) — 대상 사용자가 소속된 회사 안에서만
    // 센다. 전사 조회라 "마지막 ADMIN"의 단위는 시스템 전체가 아니라 각 회사다: 어떤 회사의 활성 ADMIN을
    // 모두 강등/정지하면 그 회사는 자체 관리자 콘솔(/api/admin/**) 접근 수단을 영구히 잃는다.
    long countByCompanyIdAndRoleAndStatus(Long companyId, Role role, UserStatus status);

    boolean existsByEmail(String email);
}
