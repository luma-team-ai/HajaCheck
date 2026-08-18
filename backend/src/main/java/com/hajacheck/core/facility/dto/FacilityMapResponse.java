package com.hajacheck.core.facility.dto;

import java.util.List;

/**
 * 지도 전용 경량 엔드포인트 응답(#1656) — GET /api/facilities/map.
 * 이슈 #1656 "API 계약" 섹션의 {@code { facilities: [...] } } 배열 래핑 계약을 그대로 반영한다
 * (프론트 #1657 이 이 계약을 참조 — 임의 변경 금지).
 */
public record FacilityMapResponse(List<FacilityMapItemResponse> facilities) {

    public static FacilityMapResponse of(List<FacilityMapItemResponse> facilities) {
        return new FacilityMapResponse(facilities);
    }
}
