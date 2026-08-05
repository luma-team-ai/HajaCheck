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
     * @param defect         확정된 하자
     * @param defectLocation 하자 발생 부위 위치 텍스트(Defect.location 유효 시 사용, 없으면 "위치 미입력")
     */
    public static ReportRequest.ConfirmedDefect from(Defect defect, String defectLocation) {
        String typeLabel = defect.getType().label();
        String gradeLabel = gradeLabel(defect.getGrade());
        String description = buildDescription(defect, typeLabel, gradeLabel);
        return new ReportRequest.ConfirmedDefect(defect.getId(), typeLabel, defectLocation, gradeLabel, description);
    }

    // 원본 정밀안전진단 보고서 양식은 등급을 "등급 E"가 아니라 소문자 단일 글자(a~d)로만
    // 표기한다(§가. 진단 외관조사결과 상태평가 컬럼과 동일 관례, #1499 후속) — 여기서는 표시용
    // 문장에만 소문자를 쓰고, severity_grade 필드(gradeLabel 원본)는 그대로 둔다(AI 요청·등급
    // 비교 로직이 참조하는 실값이라 케이스를 바꾸면 영향 범위가 커진다).
    private static String buildDescription(Defect defect, String typeLabel, String gradeLabel) {
        String displayGrade = gradeLabel.toLowerCase();
        if (defect.getType() == DefectType.CRACK) {
            return "%s(%s) — 폭 %s, 길이 %s 균열이 관찰됨".formatted(
                    typeLabel, displayGrade, measurement(defect.getCrackWidthMm()), measurement(defect.getCrackLengthMm()));
        }
        return "%s(%s)로 판정됨".formatted(typeLabel, displayGrade);
    }

    private static String measurement(Double valueMm) {
        return valueMm != null ? "%.1fmm".formatted(valueMm) : "미측정";
    }

    private static String gradeLabel(DefectGrade grade) {
        return grade != null ? grade.name() : UNCLASSIFIED_GRADE_LABEL;
    }
}
