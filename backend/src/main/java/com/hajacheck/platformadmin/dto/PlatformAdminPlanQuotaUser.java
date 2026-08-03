package com.hajacheck.platformadmin.dto;

import com.hajacheck.membership.entity.PlanName;

/**
 * GET /api/platform-admin/plans-quota 표의 한 행 — 전사 사용자 1명의 소속 회사 플랜과 이번 달 쿼터
 * 사용 현황(#624, frontend PlanQuotaUser 대응). plan/quotaLimit/remainingDays/companyName 은 사용자가
 * 소속된 회사의 활성 구독(user_plans, company 귀속)에서 나온다 — 회사 미소속(개인 계정)이거나 회사에
 * 활성 구독이 없으면 plan/quotaLimit/remainingDays 는 전부 null 이고 status 는 EXPIRED.
 *
 * <p>quotaUsed(#1407 후속): 쿼터는 <b>회사 단위로 풀링</b>되어 usage_counters 가 회사(UserPlan) 1행에
 * 누적된다 — 사용자 개인별 소비량이 아니다. 그래서 이 값은 "이 사용자가 분석한 장수"가 아니라
 * <b>"이 사용자가 소속된 회사가 이번 달 분석한 전체 장수"</b>이고, 같은 회사 소속 사용자는 전부 같은
 * 값을 본다(quotaLimit 도 동일 원리로 회사 공통값). 무제한(quotaLimit == null) 플랜도 실제 사용량을
 * 그대로 보여준다 — 예전엔 media(검수자 배정 건수) 근사치를 개인별로 나눠 세다 보니 실사용량이 있어도
 * 0으로 보이는 경우가 있었다.
 */
public record PlatformAdminPlanQuotaUser(
        Long id,
        String name,
        String email,
        Long companyId,
        String companyName,
        PlanName plan,
        int quotaUsed,
        Integer quotaLimit,
        Long remainingDays,
        PlatformAdminPlanQuotaStatus status) {
}
