package com.hajacheck.core.dashboard.dto;

import com.hajacheck.core.defect.entity.DefectGrade;

/**
 * GET /api/dashboard/grade-distribution 응답 항목 — frontend GradeDistributionItem { grade, percent } 과 1:1.
 *
 * <p>하자가 <b>한 건이라도 있으면</b> A~E 5개 전부 반환한다(집계가 없는 등급은 percent=0) —
 * 막대그래프가 A~E 를 모두 보여주고 합계 100% 가 성립해야 하기 때문(스토리보드 DASH-01 V2).
 *
 * <p>하자가 <b>0건이면 빈 목록</b>을 반환한다(#347). 0% 5건을 내려보내면 프론트의 빈 상태 가드가
 * 발동하지 못하고 합계가 0% 라 V2 검증도 깨진다 — "데이터 없음"과 "전 등급 0%"는 다른 상태다.
 */
public record GradeDistributionResponse(String grade, double percent) {

    public static GradeDistributionResponse of(DefectGrade grade, long count, long total) {
        double percent = total == 0 ? 0.0 : Math.round(count * 1000.0 / total) / 10.0;
        return new GradeDistributionResponse(grade.name(), percent);
    }
}
