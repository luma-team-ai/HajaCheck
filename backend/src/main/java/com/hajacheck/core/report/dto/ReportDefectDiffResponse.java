package com.hajacheck.core.report.dto;

import com.hajacheck.core.defect.entity.DefectType;
import java.util.List;

/**
 * 보고서 본문(detail.items)과 현재 확정 하자 목록의 차이(#1653 P2) — grounding-recheck·resync-defects
 * 두 엔드포인트가 공통으로 반환한다. defectId 기준 비교이며, defectId가 없는(구버전 저장분) 항목은
 * 어느 쪽에도 집계되지 않는다(비교 불가능한 항목을 임의로 누락/잉여로 단정하지 않는다).
 */
public record ReportDefectDiffResponse(
        // 확정 하자에는 있지만 보고서 본문에는 없는 항목 — resync-defects 호출 시 새로 추가된다.
        List<MissingDefectItem> missingDefects,
        // 보고서 본문에는 있지만 더 이상 확정 하자가 아닌 항목 — resync-defects 호출 시 제거된다.
        List<ExtraDefectItem> extraItems) {

    public boolean hasDiff() {
        return !missingDefects.isEmpty() || !extraItems.isEmpty();
    }

    public record MissingDefectItem(
            Long defectId, DefectType defectType, String typeLabel, String severityGrade, String location) {
    }

    public record ExtraDefectItem(Long defectId, String defectType, String severityGrade) {
    }
}
