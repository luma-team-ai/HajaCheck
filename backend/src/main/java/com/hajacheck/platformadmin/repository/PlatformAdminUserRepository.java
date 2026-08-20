package com.hajacheck.platformadmin.repository;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.platformadmin.dto.PlatformAdminUserProjection;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    // 사용자 행 잠금 — 용도가 둘이다.
    //
    // ① 스킬 변경(changeSkill) delete-then-insert 원자성 보호(PR머신 2차 검토 P2). 대상 상담사 행을
    //    먼저 잠가 동일 상담사에 대한 동시 스킬 교체 요청을 직렬화한다 — 그렇지 않으면 두 요청의 DELETE가
    //    서로의 신규 INSERT를 보지 못해(READ COMMITTED) counselor_skills에 행이 2개 남을 수 있다
    //    (CompanyRepository#findByIdForUpdate와 동일 패턴 — 값 자체는 쓰지 않고 잠금 획득 용도).
    // ② changeRole/changeStatus 의 **로드 그 자체**(#1492 PR머신 2차 검토 P2). 이쪽은 반환 엔티티를
    //    그대로 써야 한다 — User 에 @DynamicUpdate·@Version 이 없어 UPDATE 내용이 "로드 시점 스냅샷"이라,
    //    ①처럼 잠금 없이 먼저 로드한 뒤 이 메서드를 부르면 L1 캐시의 stale 스냅샷이 그대로 UPDATE 되어
    //    초대 코드 redeem 이 쓴 company_id 를 덮는다(PlatformAdminUserService#findUserForUpdate javadoc).
    //
    // ⚠️ 이 잠금이 걸린 경로의 잠금 순서 계약(users → companies / users → usage_counters)의 단일 소스는
    // UserRepository#findByIdForUpdate javadoc 이다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);
}
