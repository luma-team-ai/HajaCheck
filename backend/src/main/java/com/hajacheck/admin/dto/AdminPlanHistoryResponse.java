package com.hajacheck.admin.dto;

import java.util.List;

/**
 * GET /api/admin/plan/history 응답 — 회사 구독 변경 이력(최신 순, #507). 각 항목이 user_plans 행 하나이며
 * startedAt/endedAt·planName·status 로 "이전값→이후값·언제"를 표현한다. page 는 1-base(plan-quota 와 정합,
 * PR#525 머신 리뷰 P3 — 무페이지네이션 반환 지적으로 추가).
 */
public record AdminPlanHistoryResponse(
        List<AdminPlanHistoryEntry> history,
        int page,
        int size,
        long totalElements) {
}
