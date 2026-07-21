package com.hajacheck.admin.repository;

import com.hajacheck.admin.dto.AdminUserProjection;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UserPlanStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminUserRepository extends JpaRepository<User, Long> {

    // 관리자 사용자 관리(#405) — 활성 구독(user_plans.status=ACTIVE)을 left join 해 plan 을 조회한다.
    // uq_user_plans_active_user 제약(사용자당 활성 구독 최대 1건)이 있어 행 중복(fan-out)이 발생하지 않는다.
    // 각 필터는 값이 없으면(null) 조건을 건너뛴다 — Spring Data 파생 쿼리로는 이 동적 조합을 표현할 수 없어 JPQL 사용.
    // role/status/plan 은 "(:x is null or ...)" 대신 별도 has* boolean 플래그로 우회한다: PG 확장 프로토콜은
    // enum 타입 바인드 파라미터가 "IS NULL" 비교에만 단독으로 쓰이면(동등비교 문맥이 없으면) 타입을 추론하지 못해
    // "could not determine data type of parameter"로 실패한다(keyword 는 String 이라 영향 없음).
    // 활성 구독이 없는 사용자는 화면에 Free로 보여지므로(AdminUserResponse#from), plan=FREE 필터는
    // p.name=FREE 인 사용자뿐 아니라 활성 구독이 아예 없는(p.name is null) 사용자도 포함해야 한다.
    // companyId는 항상 강제한다(옵션 아님) — 이 화면은 기업 관리자 화면이라 관리자는 자기 회사 소속
    // 사용자만 봐야 한다(플랫폼 관리자 화면은 별도로 만들 예정, 그쪽에서만 전사 조회를 허용한다).
    @Query("""
            select new com.hajacheck.admin.dto.AdminUserProjection(
                u.id, u.name, u.email, u.profileImageUrl, u.role, p.name, u.createdAt, u.lastLoginAt, u.status)
            from User u
            left join UserPlan up on up.userId = u.id and up.status = :activeStatus
            left join Plan p on p.id = up.planId
            where u.companyId = :companyId
              and (:keyword is null or lower(u.name) like :keyword escape '\\' or lower(u.email) like :keyword escape '\\')
              and (:hasRole = false or u.role = :role)
              and (:hasStatus = false or u.status = :status)
              and (:hasPlan = false or p.name = :plan or (p.name is null and :planIsFree = true))
            order by u.createdAt desc
            """)
    Page<AdminUserProjection> search(
            @Param("companyId") Long companyId,
            @Param("keyword") String keyword,
            @Param("hasRole") boolean hasRole,
            @Param("role") Role role,
            @Param("hasStatus") boolean hasStatus,
            @Param("status") UserStatus status,
            @Param("hasPlan") boolean hasPlan,
            @Param("plan") PlanName plan,
            @Param("planIsFree") boolean planIsFree,
            @Param("activeStatus") UserPlanStatus activeStatus,
            Pageable pageable);

    Optional<User> findByIdAndCompanyId(Long id, Long companyId);

    long countByCompanyId(Long companyId);

    long countByCompanyIdAndStatus(Long companyId, UserStatus status);

    long countByCompanyIdAndCreatedAtBetween(Long companyId, LocalDateTime from, LocalDateTime to);

    boolean existsByEmail(String email);
}
