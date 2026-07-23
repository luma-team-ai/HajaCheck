package com.hajacheck.core.analysis.dto;

import java.util.List;
import java.util.Map;

/**
 * AI 분석 실행/상태 폴링 응답(dev-05-04, AP-006 연장) — {@code GET /api/inspections/{id}/analyze}.
 *
 * <p>{@code stage}는 프론트 5단계 트래커 키와 그대로 맞춘 문자열이다: upload/frameExtraction/
 * aiDetection/postProcessing/done. 이미지 전용 회차(dev-05-03 범위)는 영상 프레임 추출이 없어
 * frameExtraction은 실질적으로 즉시 통과하는 무동작 단계다(InspectionAnalysisService 참고).
 *
 * <p>{@code severityDistribution}은 등급별 "개수"(A~E)다 — 퍼센트 변환은 프론트가 total로 나눠 계산한다
 * (원시값을 내려줘야 total=0일 때도 클라이언트가 안전하게 처리할 수 있다).
 */
public record AnalysisStatusResponse(
        Long inspectionId,
        String stage,
        int progressPercent,
        int totalFileCount,
        int analyzedFileCount,
        List<FileProgress> files,
        int detectedDefectCount,
        int riskyCrackCount,
        Map<String, Integer> severityDistribution,
        int failedCount) {

    /** {@code status}는 waiting/analyzing/completed/failed. */
    public record FileProgress(
            Long mediaId,
            String fileName,
            String status,
            Integer defectCount,
            String elapsedOrEta) {
    }
}
