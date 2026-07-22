package com.hajacheck.core.report.service;

import com.hajacheck.core.ai.dto.ReportRequest;
import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectType;

/**
 * 확정된 {@link Defect} 로부터 AI 보고서 요청용 위치·설명 텍스트를 자동 생성하는 순수 함수(#446).
 * Defect 엔티티에는 자유 텍스트 location/description 필드가 없어(bbox 좌표·치수만 보유),
 * 여기서 한국어 문장 템플릿으로 조립한다.
 */
public final class ConfirmedDefectTextFactory {

    private static final String UNCLASSIFIED_GRADE_LABEL = "미분류";

    private ConfirmedDefectTextFactory() {
    }

    /**
     * @param defect   확정된 하자
     * @param location 하자가 위치한 시설물 주소(Defect→Inspection→Facility.address) — 호출부(서비스)가 조회해 전달한다.
     */
    public static ReportRequest.ConfirmedDefect from(Defect defect, String location) {
        String typeLabel = defect.getType().label();
        String gradeLabel = gradeLabel(defect.getGrade());
        String description = buildDescription(defect, typeLabel, gradeLabel);
        return new ReportRequest.ConfirmedDefect(typeLabel, location, gradeLabel, description);
    }

    private static String buildDescription(Defect defect, String typeLabel, String gradeLabel) {
        if (defect.getType() == DefectType.CRACK) {
            return "%s(등급 %s) — 폭 %s, 길이 %s 균열이 관찰됨".formatted(
                    typeLabel, gradeLabel, measurement(defect.getCrackWidthMm()), measurement(defect.getCrackLengthMm()));
        }
        return "%s(등급 %s)로 판정됨".formatted(typeLabel, gradeLabel);
    }

    private static String measurement(Double valueMm) {
        return valueMm != null ? "%.1fmm".formatted(valueMm) : "미측정";
    }

    private static String gradeLabel(DefectGrade grade) {
        return grade != null ? grade.name() : UNCLASSIFIED_GRADE_LABEL;
    }
}
