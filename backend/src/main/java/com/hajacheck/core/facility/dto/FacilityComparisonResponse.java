package com.hajacheck.core.facility.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 회차 간 비교 응답(HAJA-531/#1112) — 프론트 InspectionComparisonResult와 1:1 대응하되,
 * 실 데이터 근거가 없는 beforeImageUrl/afterImageUrl/crackTrend는 이번 범위에서 제외한다
 * (2026-07-28 사용자 결정 — 별도 이슈 없이 생략, 프론트가 그 필드를 참조하면 별도 처리 필요).
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
        List<CycleOption> availableCycles
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
