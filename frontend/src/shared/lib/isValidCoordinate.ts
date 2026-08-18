// 좌표 유효성 검증 — 지도(map) 마커 렌더링과 시설물 목록(facility) 배지가 동일한 판정 기준을
// 공유해야 "목록엔 있는데 마커는 없다" 같은 불일치가 재발하지 않는다(#1657). 기존에는
// features/map/lib/createFacilityMarker.ts에만 있던 로컬 함수였으나, 목록 쪽(FacilityListPanel의
// '좌표 없음' 배지)도 같은 판정이 필요해져 shared로 승격했다(React_코드_컨벤션.md §1 — feature 간
// 공유가 필요해지면 shared로 승격).
//
// 서버가 null/NaN/범위밖 좌표를 줄 수 있어 마커 생성 전 런타임 검증이 필요하다.
// (0,0)은 EXIF GPS 태그 결측/손상 시 흔한 직렬화 결과(기니만 앞바다)이므로 유효 좌표로 취급하지 않는다.
export function isValidCoordinate(
  latitude: number | null | undefined,
  longitude: number | null | undefined,
): boolean {
  return (
    typeof latitude === 'number' &&
    typeof longitude === 'number' &&
    Number.isFinite(latitude) &&
    Number.isFinite(longitude) &&
    latitude >= -90 &&
    latitude <= 90 &&
    longitude >= -180 &&
    longitude <= 180 &&
    !(latitude === 0 && longitude === 0)
  );
}

/**
 * 객체 형태(facility)의 latitude/longitude를 함께 좁혀주는 타입가드 버전. 목록 API가 좌표 없는
 * 시설물도 그대로 내려주게 되면서(#1657 — 목록엔 남기고 마커에서만 제외) latitude/longitude가
 * `number | null`이 된 도메인 타입에서, "이 시설물은 지도에 표시 가능하다"를 한 번에 좁혀 쓰기 위함.
 */
export function hasValidCoordinate<T extends { latitude: number | null; longitude: number | null }>(
  facility: T,
): facility is T & { latitude: number; longitude: number } {
  return isValidCoordinate(facility.latitude, facility.longitude);
}
