package com.hajacheck.core.facility.dto;

import com.hajacheck.core.defect.entity.DefectGrade;
import java.time.LocalDate;
import java.util.List;

/**
 * 시설물 상세 "점검 이력" 탭 응답(#1359/HAJA-616) — 프론트 FacilityInspectionOverview와 1:1 대응.
 *
 * <p>overallGrade는 facilities.initial_grade(등록 시 입력값)와 완전히 다른 개념이다 — 최신 회차
 * 하자 중 최악 등급의 계산값이며, null이면 최신 회차에 등급이 매겨진 하자가 없다는 뜻이다
 * (V10 마이그레이션·FacilityInitialGrade 주석 참고 — 두 개념을 혼용하지 않는다).
 */
public record FacilityInspectionOverviewResponse(
        DefectGrade overallGrade,
        int totalRounds,
        long cumulativeDefectCount,
        long unresolvedDefectCount,
        List<HistoryItem> history
) {
    public record HistoryItem(
            Long id,
            Integer roundNo,
            LocalDate inspectionDate,
            String inspectorName,
            String status,
            long imageCount,
            List<GradeCount> defectGradeBreakdown,
            /** 이전 회차 대비 변화 메모 — 최신 회차에만 존재(null 아니면 표시) */
            String changeNote,
            /** 미리보기 썸네일 URL(최대 2장) — 최신 회차에만 채워짐(#1549, changeNote와 동일 관례).
             * 이전 회차는 빈 리스트. */
            List<String> thumbnailUrls
    ) {
    }

    public record GradeCount(DefectGrade grade, long count) {
    }
}
