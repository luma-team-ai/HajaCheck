package com.hajacheck.core.dashboard.dto;

import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectType;
import com.hajacheck.core.facility.entity.Facility;
import java.time.LocalDateTime;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * GET /api/dashboard/pending-priority 응답 항목 — frontend PendingPriorityItem
 * { id, inspectionId, grade, title, location, occurredAt } 과 1:1.
 *
 * <p>title 은 defects.type 의 한글 라벨(DDL 코멘트 기준). location 은 시설물 명칭 + 유형 + 주소를
 * " · "로 이어붙인 문자열이다(FacilityCard.tsx의 subtitleParts 조합과 동일 컨벤션) — Figma 시안의
 * "지하 3층 기둥" 같은 하자별 세부 위치(층·구역)는 defects/media 스키마에 해당 컬럼이 없어 재현
 * 불가하다(후속 계약에서 media/defect 세부 위치 필드 논의 필요). 대신 이미 존재하는 Facility 필드
 * (type/address)만으로 "이름만" 표기보다 정보량을 높인 절충안이다.
 *
 * <p>inspectionId 는 프론트가 이 항목에서 하자 상세(점검 회차 스코프)로 바로 이동할 수 있도록 함께 내려준다.
 */
public record PendingPriorityResponse(
        Long id,
        Long inspectionId,
        String grade,
        String title,
        String location,
        LocalDateTime occurredAt
) {

    public static PendingPriorityResponse from(Defect defect, Facility facility) {
        return new PendingPriorityResponse(
                defect.getId(),
                defect.getInspectionId(),
                defect.getGrade() == null ? null : defect.getGrade().name(),
                typeLabel(defect.getType()),
                locationOf(facility),
                defect.getCreatedAt());
    }

    private static String locationOf(Facility facility) {
        if (facility == null) {
            return "-";
        }
        String location = Stream.of(facility.getName(), facility.getType(), facility.getAddress())
                .filter(part -> part != null && !part.isBlank())
                .collect(Collectors.joining(" · "));
        return location.isBlank() ? "-" : location;
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
