package com.hajacheck.platformadmin.dto;

/**
 * 서비스 통계(#633) 분석 추이 — frontend AnalysisRequestTrendPoint 1:1. {@code requests} 필드명은
 * 유지하지만 값은 요청 건수가 아니라 <b>분석한 이미지 장수</b>(usage_counters.analyzedImageCount)다
 * (#1407 후속 — frontend 라벨 "분석 요청 장수"와 정합).
 */
public record PlatformAdminAnalysisRequestTrendPoint(String month, long requests) {
}
