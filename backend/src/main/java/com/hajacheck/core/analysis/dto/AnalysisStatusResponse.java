package com.hajacheck.core.analysis.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * AI 분석 실행/상태 폴링 응답(dev-05-04, AP-006 연장) — {@code GET /api/inspections/{id}/analyze}.
 *
 * <p>{@code stage}는 프론트 5단계 트래커 키와 그대로 맞춘 문자열이다: upload/frameExtraction/
 * aiDetection/postProcessing/done(+failed, 코드 리뷰 P2). 이미지 전용 회차(dev-05-03 범위)는
 * 영상 프레임 추출이 없어 frameExtraction은 실질적으로 즉시 통과하는 무동작 단계다
 * (InspectionAnalysisService 참고).
 *
 * <p>{@code severityDistribution}은 등급별 "개수"(A~E)다 — 퍼센트 변환은 프론트가 total로 나눠 계산한다
 * (원시값을 내려줘야 total=0일 때도 클라이언트가 안전하게 처리할 수 있다).
 *
 * <p>{@code updatedAt}(코드 리뷰 P2, 하트비트)은 이 진행률이 마지막으로 갱신된 시각 — 워커가
 * JVM 재기동·OOM 등으로 크래시해도 Redis 진행률 캐시(TTL 6시간)는 살아남으므로,
 * InspectionAnalysisService가 이 값으로 "진짜 진행 중"과 "고착"을 구분한다.
 *
 * <p>{@code unanalyzedMediaCount}(#1654 증분 분석) — 이 회차의 원본 촬영사진 중 아직 AI 분석을
 * 거치지 않은(media.analyzed_at IS NULL) 장수. 프론트가 stage=='done'일 때 이 값이 0보다 크면
 * "추가 사진 N장 분석" 액션을 노출한다(같은 POST /analyze 엔드포인트를 재호출하면 서버가 자동으로
 * 증분 분석으로 처리한다 — 새 엔드포인트 없음). 진행 중(aiDetection) 스냅샷에서는 "이번 실행에서
 * 아직 처리 안 된(=실패로 남은) 이미지 수"의 근사치로 failedCount를 그대로 쓴다(worker 참고).
 *
 * <p>{@code reanalysisAllowed}(리뷰 P1 픽스, #1654) — 이 회차가 <b>지금</b> {@code POST /analyze}를
 * 다시 받아들일 상태인지(=InspectionAnalysisService의 재분석 허용 소스 상태 {@code
 * ANALYSIS_ALLOWED_SOURCE_STATUSES}에 속하는지). {@code unanalyzedMediaCount}만으로 "추가 사진 분석"
 * 버튼을 노출하면, REVIEWED/REPORTED(검수·보고서 확정) 회차에 미분석 사진이 남아있는 예외적인
 * 경우에도 버튼이 뜨는데, 클릭하면 서버가 항상 ANALYSIS_NOT_ALLOWED로 거부하는 <b>죽은 버튼</b>이
 * 된다. 프론트는 두 조건을 모두 만족할 때만({@code unanalyzedMediaCount > 0 && reanalysisAllowed})
 * 그 버튼을 보여준다. 진행 중(aiDetection) 스냅샷에서는 이미 ANALYZING이라 다시 트리거할 수 없으므로
 * 항상 false다.
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
        int failedCount,
        int unanalyzedMediaCount,
        boolean reanalysisAllowed,
        Instant updatedAt) {

    /** {@code status}는 waiting/analyzing/completed/failed. */
    public record FileProgress(
            Long mediaId,
            String fileName,
            String status,
            Integer defectCount,
            String elapsedOrEta) {
    }

    /** 고착 감지(코드 리뷰 P2) 시 표시용 stage만 바꾼 복사본을 만든다 — updatedAt 등 나머지 값은 그대로 유지해 마지막으로 알려진 진행 정보를 보존한다. */
    public AnalysisStatusResponse withStage(String newStage) {
        return new AnalysisStatusResponse(inspectionId, newStage, progressPercent, totalFileCount,
                analyzedFileCount, files, detectedDefectCount, riskyCrackCount, severityDistribution,
                failedCount, unanalyzedMediaCount, reanalysisAllowed, updatedAt);
    }
}
