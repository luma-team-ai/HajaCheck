package com.hajacheck.platformadmin.dto;

/**
 * 서비스 통계(#633) 상담 유형 분포 — frontend CounselTypeDistributionItem 1:1.
 *
 * <p>이번 구현 범위 밖(사용자 지시, 2026-07-23) — counsel 도메인에 USAGE/ANALYSIS_RESULT/BILLING_ETC 유형을
 * 분류할 데이터가 아직 없어(repository/service 미구현, bot_scenarios.category 는 무관한 자유 텍스트)
 * {@link com.hajacheck.platformadmin.service.PlatformAdminServiceStatsService} 는 항상 빈 목록을 반환한다.
 * 후속 이슈에서 counsel_tickets 분류 컬럼이 생기면 이 레코드를 채워 넣는다.
 */
public record PlatformAdminCounselTypeDistributionItem(String type, long count) {
}
