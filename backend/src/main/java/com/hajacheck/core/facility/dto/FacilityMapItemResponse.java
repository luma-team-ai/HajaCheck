package com.hajacheck.core.facility.dto;

import java.math.BigDecimal;

/**
 * 지도 전용 시설물 마커 항목(#1656) — GET /api/facilities/map 의 facilities 배열 원소.
 * Entity 직접 노출 금지(§0), 지도 마커 렌더링에 필요한 필드만 담는다.
 *
 * <p><b>facilityType 필드명(이슈 #1656 계약 원문 그대로)</b> — 이슈 본문 "API 계약" 섹션의 JSON 스니펫이
 * {@code facilityType} 으로 명시돼 있고 프론트 #1657 이 이 스니펫을 그대로 참조한다(임의 변경 금지).
 * 같은 이슈 본문의 다른 구절("기존 목록 응답 필드명과 동일 유지")은 FacilityResponse.type 과 이름이
 * 상충하는데, 계약으로 명시된 스니펫을 우선했다 — 프론트 구현 시 실제 소비 필드명과 일치하는지 재확인 필요.
 */
public record FacilityMapItemResponse(
        Long id,
        String name,
        BigDecimal latitude,
        BigDecimal longitude,
        String facilityType,
        String highestGrade,
        Long warningCount,
        Long cautionCount,
        String thumbnailUrl) {

    public static FacilityMapItemResponse of(
            Long id,
            String name,
            BigDecimal latitude,
            BigDecimal longitude,
            String facilityType,
            String highestGrade,
            Long warningCount,
            Long cautionCount,
            String thumbnailUrl) {
        return new FacilityMapItemResponse(
                id, name, latitude, longitude, facilityType, highestGrade, warningCount, cautionCount, thumbnailUrl);
    }
}
