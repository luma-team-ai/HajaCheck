package com.hajacheck.core.facility.repository;

import java.math.BigDecimal;

/**
 * 지도 전용 경량 프로젝션(#1656) — {@link FacilityRepository#findMapProjectionsByCompanyId} 반환용.
 * 목록/CRUD 응답(FacilityResponse)이 쓰는 전체 엔티티 로딩 대신, 지도 마커 렌더링에 필요한
 * 좌표·유형 최소 컬럼만 프로젝션한다(다른 배치 프로젝션과 동일하게 최상위 파일로 둔다).
 *
 * <p><b>address 추가(#1656 리뷰 보강, #1657 리뷰에서 발견)</b> — 구 지도 흐름(GET /facilities)의
 * address가 지도 검색(주소 매칭)·지오코딩 백필에 쓰이는데, 최초 경량 계약에서 빠져 주소 검색이
 * 조용히 무력화되는 회귀가 있었다. JPQL 프로젝션 컬럼 하나만 늘려 경량 취지는 그대로 유지한다.
 */
public interface FacilityMapProjection {
    Long getId();

    String getName();

    String getType();

    String getAddress();

    BigDecimal getLatitude();

    BigDecimal getLongitude();
}
