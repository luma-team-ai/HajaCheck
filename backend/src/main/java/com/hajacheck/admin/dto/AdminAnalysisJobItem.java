package com.hajacheck.admin.dto;

import java.time.LocalDate;

/**
 * 관리자 AI 분석 현황 모니터링(신규) 목록 항목 — {@code jobId}는 별도 "분석 실행" 테이블이 없어
 * inspection.id를 그대로 재사용한다(AiAnalysisStatusPage의 "점검 ID" 표시와 동일한 결정).
 *
 * <p>{@code progressPercent}는 {@code status=ANALYZING}일 때만 값이 있을 수 있고, 그마저도
 * AnalysisProgressStore(Redis, TTL 있음) 캐시가 없으면 null이다(fail-soft — 진행률을 못 구해도
 * "진행중"이라는 사실 자체는 DB 기준으로 신뢰할 수 있으므로 화면 전체를 실패시키지 않는다).
 */
public record AdminAnalysisJobItem(
        Long jobId,
        String facilityName,
        Long inspectorId,
        String inspectorName,
        LocalDate inspectionDate,
        AdminAnalysisJobStatus status,
        Integer progressPercent) {
}
