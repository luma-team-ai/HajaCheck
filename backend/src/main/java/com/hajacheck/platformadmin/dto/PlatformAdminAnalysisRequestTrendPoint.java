package com.hajacheck.platformadmin.dto;

/** 서비스 통계(#633) 분석 요청 추이 — frontend AnalysisRequestTrendPoint 1:1. */
public record PlatformAdminAnalysisRequestTrendPoint(String month, long requests) {
}
