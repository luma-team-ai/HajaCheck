package com.hajacheck.core.media.repository;

/**
 * 시설물 목록 썸네일 배치 조회(HAJA-367/#670) 프로젝션 — {@link MediaRepository#findFirstIdsByFacilityIds}
 * 반환용. 다른 배치 프로젝션(예: FacilityLatestDefectProjection)과 동일하게 최상위 파일로 둔다.
 */
public interface FacilityRepresentativeMediaProjection {
    Long getFacilityId();

    Long getMediaId();
}
