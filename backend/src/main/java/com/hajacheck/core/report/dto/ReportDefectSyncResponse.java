package com.hajacheck.core.report.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.hajacheck.core.report.entity.ReportStatus;
import java.time.LocalDateTime;

/**
 * grounding-recheck·resync-defects 공통 응답(#1653 P2). PR머신 리뷰 P1 — 최초엔 {@code {report, diff}}
 * 중첩 구조였으나, 이 두 엔드포인트도 다른 보고서 API처럼 {@code data.groundingCheckPassed} 등을
 * 최상위에서 그대로 읽는 프론트 관례를 깨(하위호환 파괴) 회귀였다. {@link ReportDetailResponse}의 모든
 * 필드를 최상위에 그대로 평탄화하고, {@code diff}만 추가 필드로 얹는다 — 기존 소비처는 무회귀,
 * diff는 이 두 엔드포인트에만 존재하는 additive 필드다.
 *
 * <p>grounding-recheck는 진단만 하고 본문을 바꾸지 않으므로 diff가 "지금 resync하면 이렇게 바뀐다"를
 * 미리 보여주는 역할이고, resync-defects는 실제로 그 diff를 적용한 뒤의 결과를 같은 모양으로 반환한다.
 */
public record ReportDefectSyncResponse(
        Long id,
        Long inspectionId,
        int version,
        JsonNode content,
        ReportStatus status,
        Boolean groundingCheckPassed,
        String pdfUrl,
        Long editedBy,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        ReportDetailResponse.ReportContext context,
        ReportDefectDiffResponse diff) {

    public static ReportDefectSyncResponse from(ReportDetailResponse report, ReportDefectDiffResponse diff) {
        return new ReportDefectSyncResponse(
                report.id(), report.inspectionId(), report.version(), report.content(), report.status(),
                report.groundingCheckPassed(), report.pdfUrl(), report.editedBy(), report.createdBy(),
                report.createdAt(), report.updatedAt(), report.context(), diff);
    }
}
