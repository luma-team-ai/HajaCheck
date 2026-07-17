package com.hajacheck.core.dashboard.dto;

import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectType;
import java.time.LocalDateTime;

/**
 * GET /api/dashboard/pending-priority 응답 항목 — frontend PendingPriorityItem
 * { id, grade, title, location, occurredAt } 과 1:1.
 *
 * <p>title 은 defects.type 의 한글 라벨(DDL 코멘트 기준). location 은 시설물 명칭까지만 제공한다 —
 * defects/media 스키마에 세부 위치(층·구역) 컬럼이 없어 Figma 시안의 "지하 3층 기둥" 같은 세부 텍스트는
 * 현재 데이터로 재현 불가(후속 계약에서 media/defect 세부 위치 필드 논의 필요).
 */
public record PendingPriorityResponse(
        Long id,
        String grade,
        String title,
        String location,
        LocalDateTime occurredAt
) {

    public static PendingPriorityResponse from(Defect defect, String facilityName) {
        return new PendingPriorityResponse(
                defect.getId(),
                defect.getGrade() == null ? null : defect.getGrade().name(),
                typeLabel(defect.getType()),
                facilityName,
                defect.getCreatedAt());
    }

    private static String typeLabel(DefectType type) {
        return switch (type) {
            case CRACK -> "균열";
            case SPALLING -> "박리·박락";
            case LEAK_EFFLORESCENCE -> "누수·백태";
            case REBAR_EXPOSURE -> "철근 노출";
            case PAINT_DAMAGE -> "도장 손상";
        };
    }
}
