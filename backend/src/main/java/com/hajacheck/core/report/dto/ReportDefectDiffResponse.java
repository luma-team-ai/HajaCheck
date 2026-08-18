package com.hajacheck.core.report.dto;

import com.hajacheck.core.defect.entity.DefectType;
import java.util.List;

/**
 * 보고서 본문(detail.items)과 현재 확정 하자 목록의 차이(#1653 P2) — grounding-recheck·resync-defects
 * 두 엔드포인트가 공통으로 반환한다. defectId 기준 비교이며, defectId가 없는(2026-08-02 이전 저장분 등
 * 구버전 콘텐츠) 항목은 missing/extra 어느 쪽으로도 단정하지 않는 대신 {@link #unmatchedItems}에 담아
 * 사용자가 "resync-defects가 이 항목은 손대지 않고 그대로 둔다"는 사실을 인지할 수 있게 한다
 * (PR머신 리뷰 P1 — 무경고로 조용히 사라지는 것을 방지, resyncDefects는 이 항목들을 보존한다).
 */
public record ReportDefectDiffResponse(
        // 확정 하자에는 있지만 보고서 본문에는 없는 항목 — resync-defects 호출 시 새로 추가된다.
        List<MissingDefectItem> missingDefects,
        // 보고서 본문에는 있지만 더 이상 확정 하자가 아닌 항목 — resync-defects 호출 시 제거된다.
        List<ExtraDefectItem> extraItems,
        // defectId가 없어 비교 자체가 불가능한 본문 항목 — resync-defects 호출 시에도 제거되지 않고
        // 그대로 보존된다(서술 유실 방지). 사용자가 수동으로 defectId를 확인해야 하는 항목의 목록.
        List<UnmatchedItem> unmatchedItems) {

    public boolean hasDiff() {
        return !missingDefects.isEmpty() || !extraItems.isEmpty();
    }

    public record MissingDefectItem(
            Long defectId, DefectType defectType, String typeLabel, String severityGrade, String location) {
    }

    public record ExtraDefectItem(Long defectId, String defectType, String severityGrade) {
    }

    public record UnmatchedItem(String defectType, String severityGrade) {
    }
}
