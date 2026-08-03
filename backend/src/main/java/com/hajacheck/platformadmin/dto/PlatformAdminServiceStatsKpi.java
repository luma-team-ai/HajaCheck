package com.hajacheck.platformadmin.dto;

/**
 * 서비스 통계(#633) KPI 4종 — frontend ServiceStatsKpi 1:1.
 *
 * <p>analysisRequests/counselCount 는 "이번 기간"(현재 트렌드 윈도우, 최근 6개월) 누적치다 — monthlySummary
 * 각 행의 analysisCount/counselCount 합계와 항상 일치한다(PlatformAdminServiceStatsService 계약).
 * {@code analysisRequests} 필드명은 유지하지만 값은 요청 건수가 아니라 <b>분석한 이미지 장수</b>다
 * (#1407 후속 — frontend 라벨 "장"과 정합).
 */
public record PlatformAdminServiceStatsKpi(
        long totalSubscribers,
        long totalSubscribersDelta,
        long newSubscribersThisMonth,
        int newSubscribersChangePercent,
        long analysisRequests,
        long counselCount
) {
}
