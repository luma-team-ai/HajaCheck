package com.hajacheck.core.facility.repository;

import java.math.BigDecimal;

/**
 * 지도 전용 경량 프로젝션(#1656) — {@link FacilityRepository#findMapProjectionsByCompanyId} 반환용.
 * 목록/CRUD 응답(FacilityResponse)이 쓰는 전체 엔티티 로딩 대신, 지도 마커 렌더링에 필요한
 * 좌표·유형 최소 컬럼만 프로젝션한다(다른 배치 프로젝션과 동일하게 최상위 파일로 둔다).
 */
public interface FacilityMapProjection {
    Long getId();

    String getName();

    String getType();

    BigDecimal getLatitude();

    BigDecimal getLongitude();
}
