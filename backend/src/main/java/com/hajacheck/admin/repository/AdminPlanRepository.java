package com.hajacheck.admin.repository;

import com.hajacheck.admin.dto.AdminPlanHistoryEntry;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.entity.UserPlanStatus;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 관리자 플랜·쿼터 관리(#507) — 회사 귀속 user_plans 조회. 마이페이지의 UserPlanRepository 와 도메인은 같지만
 * 관리자 화면 전용 쿼리(회사 스코프 이력 등)를 분리해 각 화면의 조회 계약을 독립적으로 유지한다.
 */
public interface AdminPlanRepository extends JpaRepository<UserPlan, Long> {

    // 회사의 "현재 구독" 조회 — 특정 상태(ACTIVE 우선, 없으면 UPGRADE_REQUESTED)의 최신 행.
    Optional<UserPlan> findFirstByCompanyIdAndStatusOrderByStartedAtDescIdDesc(
            Long companyId, UserPlanStatus status);

    // 회사 구독 변경 이력 — user_plans 행을 최신 순으로 페이지 단위 반환. plan_id → plan.name 조인으로
    // 요금제명을 함께 반환한다(회사 귀속 행만 대상: company_id = :companyId. 개인 귀속 행은 company_id
    // NULL 이라 자연히 제외된다). PR#525 머신 리뷰 P3: 무페이지네이션이면 이력이 누적될수록 응답이
    // 선형으로 커져 plan-quota/사용자 관리와 계약 일관성도 어긋나 페이지네이션을 추가했다.
    @Query(value = """
            select new com.hajacheck.admin.dto.AdminPlanHistoryEntry(
                up.id, p.name, up.status, up.startedAt, up.endedAt)
            from UserPlan up
            join Plan p on p.id = up.planId
            where up.companyId = :companyId
            order by up.startedAt desc, up.id desc
            """,
            countQuery = "select count(up) from UserPlan up where up.companyId = :companyId")
    Page<AdminPlanHistoryEntry> findHistoryByCompanyId(@Param("companyId") Long companyId, Pageable pageable);
}
