package com.hajacheck.core.defect.repository;

/**
 * 시설물 목록/상세 대표(최신) 하자 id 배치 조회(HAJA-434 갭1) — 시설물별 최신 하자 1건의
 * facilityId/defectId 쌍. 서비스 계층에서 facilityId 기준 Map으로 조립해 N+1을 피한다
 * (FacilityService.listStatus()의 findLatestByFacilityIds 패턴과 동일).
 */
public interface FacilityLatestDefectProjection {
    Long getFacilityId();

    Long getDefectId();
}
