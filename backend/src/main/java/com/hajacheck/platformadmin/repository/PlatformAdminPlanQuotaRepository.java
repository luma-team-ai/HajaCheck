package com.hajacheck.platformadmin.repository;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 플랫폼 관리자 콘솔 — 플랜·쿼터 관리(#624). PlatformAdminUserRepository(#576)와 마찬가지로
 * companyId 스코프 없이 전사 사용자를 대상으로 하되, 이 화면 전용 쿼리로 분리한다.
 */
public interface PlatformAdminPlanQuotaRepository extends JpaRepository<User, Long> {

    // 표(content) — PLATFORM_ADMIN 자신은 관리 대상이 아니므로 항상 제외한다(#576과 동일 계약).
    // keyword는 이름·이메일뿐 아니라 소속 기업명까지 매칭한다(PlatformAdminUserRepository#576과 동일
    // 계약, 사용자 지시). plan 필터는 회사 단위 구독이라 미리 계산해 둔 companyId 집합으로 좁힌다
    // (hasPlanFilter=false면 무시).
    @Query("""
            select u from User u
            left join Company c on c.id = u.companyId
            where u.role <> :platformAdminRole
              and (:keyword is null
                or lower(u.name) like :keyword escape '\\'
                or lower(u.email) like :keyword escape '\\'
                or lower(c.name) like :keyword escape '\\')
              and (:hasPlanFilter = false or u.companyId in :planCompanyIds)
            order by u.id asc
            """)
    Page<User> search(
            @Param("keyword") String keyword,
            @Param("platformAdminRole") Role platformAdminRole,
            @Param("hasPlanFilter") boolean hasPlanFilter,
            @Param("planCompanyIds") Collection<Long> planCompanyIds,
            Pageable pageable);

    // KPI 통계(stats) — 검색어와 무관한 전체 기준. 유효(비만료) 플랜을 가진 회사 소속 사용자만 센다.
    List<User> findByCompanyIdInAndRoleNot(Collection<Long> companyIds, Role role);

    long countByCompanyIdInAndRoleNot(Collection<Long> companyIds, Role role);
}
