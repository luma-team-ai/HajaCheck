package com.hajacheck.core.facility.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 회차 간 비교 응답(HAJA-531/#1112) — 프론트 InspectionComparisonResult와 1:1 대응한다.
 * beforeImageUrl/afterImageUrl은 각 회차의 대표 사진(그 회차의 첫 사진, 2026-07-31 사용자 결정)
 * URL을 담는다(HAJA-612/#1346) — 2026-07-28엔 실 데이터 근거가 없어 제외했었으나 이번에 채운다.
 * crackTrend는 화면 자체가 제거되어(#1347/PR #1348) 응답에 없다.
 *
 * <p>changeType 값은 프론트 DefectChangeType(new/worsened/unchanged/resolved/recurring)과 동일하다.
 * "재발생"(이전 회차 RESOLVED였던 하자가 이후 회차에 previousDefectId로 다시 연결된 경우)은
 * recurring으로 별도 분류한다(HAJA-532/#1119 — 초기(#1112)엔 대응 타입이 없어 worsened로 근사 매핑했었음).
 */
public record FacilityComparisonResponse(
        Long facilityId,
        String facilityName,
        CycleOption beforeCycle,
        CycleOption afterCycle,
        List<ComparisonKpi> kpis,
        List<DefectChangeRow> changes,
        List<CycleOption> availableCycles,
        String beforeImageUrl,
        String afterImageUrl
) {
    public record CycleOption(Integer cycle, LocalDate date) {
    }

    public record ComparisonKpi(String key, String label, long value, long changeValue) {
    }

    public record DefectChangeRow(
            Long id,
            String location,
            String defectType,
            String gradeBefore,
            String gradeAfter,
            String changeType,
            String note
    ) {
    }
}
