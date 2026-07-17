package com.hajacheck.core.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * FastAPI {@code POST /ai/briefing} 요청 바디 — {@code DashboardStats}(openapi.yaml) 대응.
 * 값은 {@link com.hajacheck.core.ai.service.BriefingStatsService} 가 로그인 사용자 소유 범위로
 * 집계한 실측치다(#248 / HAJA-197). JSON 키는 FastAPI 계약과 동일하게 snake_case 유지
 * (AiProxyService 가 그대로 직렬화해 전달 — DefectExplainRequest 와 동일 패턴).
 */
public record BriefingStatsRequest(
        @JsonProperty("total_facilities") long totalFacilities,
        @JsonProperty("monthly_analysis") long monthlyAnalysis,
        @JsonProperty("pending_review") long pendingReview,
        @JsonProperty("pending_action") long pendingAction,
        @JsonProperty("this_week_defects") long thisWeekDefects,
        @JsonProperty("last_week_defects") long lastWeekDefects,
        @JsonProperty("top_defect_type") String topDefectType,
        @JsonProperty("critical_defects") long criticalDefects,
        @JsonProperty("grade_distribution") Map<String, Long> gradeDistribution) {
}
