package com.hajacheck.core.defect.repository;

/**
 * 시설물 카드 하자건수 배지(HAJA-515/#1075) — 시설물별 비삭제 하자 총건수 배치 조회 프로젝션.
 * findLatestByFacilityIds(FacilityLatestDefectProjection)와 동일한 배치 조립 패턴 — 시설물 수만큼
 * 반복 조회하면 N+1이 되므로 대상 시설물 전체의 (facilityId, cnt) 쌍을 한 번에 가져온다.
 */
public interface FacilityDefectCountProjection {
    Long getFacilityId();

    long getCnt();
}
