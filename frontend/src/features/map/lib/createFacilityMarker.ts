// 시설물 마커 생성 — 일반 원형 도트 마커(그림자 통일) & Figma 시안 정합 선택 마커(원형 도트 + 하단 콤팩트 소형 포인터 팁 + 그림자)
import { getGradeColor, getGradeLabel } from '../constants';
import type { DefectGrade, FacilityLocation } from '../types';

// 실 API 연동(#8) 시 서버가 null/NaN/범위밖 좌표를 줄 수 있어, 마커 생성 전 런타임 검증이 필요하다.
// (0,0)은 EXIF GPS 태그 결측/손상 시 흔한 직렬화 결과(기니만 앞바다)이므로 유효 좌표로 취급하지 않는다.
export function isValidCoordinate(latitude: number, longitude: number): boolean {
  return (
    Number.isFinite(latitude) &&
    Number.isFinite(longitude) &&
    latitude >= -90 &&
    latitude <= 90 &&
    longitude >= -180 &&
    longitude <= 180 &&
    !(latitude === 0 && longitude === 0)
  );
}

/** 일반 미선택 상태 시설물 마커 (dev 브랜치 기준: 28x28 원형 도트 + 3px 백색 테두리 + 선택 핀과 통일된 드롭 섀도우) */
export function buildNormalMarkerImageSrc(color: string): string {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32" viewBox="0 0 32 32">
    <defs>
      <filter id="dot-shadow" x="-20%" y="-20%" width="140%" height="140%">
        <feDropShadow dx="0" dy="1.5" stdDeviation="1.5" flood-color="#000000" flood-opacity="0.25"/>
      </filter>
    </defs>
    <g filter="url(#dot-shadow)">
      <circle cx="16" cy="15" r="11" fill="${color}" stroke="#ffffff" stroke-width="3" />
    </g>
  </svg>`;
  return `data:image/svg+xml;base64,${btoa(svg)}`;
}

/**
 * 선택된 상태 시설물 마커 (Figma 시안 100% 픽셀 정합: 32x36 크기)
 * - 상단: 28px 직경 원형 링(3px 백색 테두리 + 내부 등급 색상 원)
 * - 하단: 과하게 길지 않은 콤팩트한 6.5px 소형 삼각형 포인터 팁
 * - 그림자: 미선택 마커와 일관되게 통일된 입체 드롭 섀도우
 */
export function buildSelectedMarkerImageSrc(color: string): string {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="32" height="36" viewBox="0 0 32 36">
    <defs>
      <filter id="pin-shadow" x="-20%" y="-20%" width="140%" height="140%">
        <feDropShadow dx="0" dy="1.5" stdDeviation="1.5" flood-color="#000000" flood-opacity="0.25"/>
      </filter>
    </defs>
    <g filter="url(#pin-shadow)">
      <path d="M 16 2 C 23.7 2 30 8.3 30 16 C 30 20.8 27.5 25 23.5 27.4 L 16 34.5 L 8.5 27.4 C 4.5 25 2 20.8 2 16 C 2 8.3 8.3 2 16 2 Z" fill="#ffffff" />
      <path d="M 16 5 C 22.1 5 27 9.9 27 16 C 27 19.8 25 23.1 21.8 25 L 16 30.5 L 10.2 25 C 7 23.1 5 19.8 5 16 C 5 9.9 9.9 5 16 5 Z" fill="${color}" />
    </g>
  </svg>`;
  return `data:image/svg+xml;base64,${btoa(svg)}`;
}

export function createMarkerImage(color: string, isSelected = false): KakaoMarkerImage {
  const PointConstructor = window.kakao?.maps?.Point;
  if (isSelected) {
    const offset = PointConstructor ? new PointConstructor(16, 35) : undefined;
    return new window.kakao.maps.MarkerImage(
      buildSelectedMarkerImageSrc(color),
      new window.kakao.maps.Size(32, 36),
      offset ? { offset } : undefined,
    );
  }
  const offset = PointConstructor ? new PointConstructor(16, 15) : undefined;
  return new window.kakao.maps.MarkerImage(
    buildNormalMarkerImageSrc(color),
    new window.kakao.maps.Size(32, 32),
    offset ? { offset } : undefined,
  );
}

export function updateFacilityMarkerImage(
  marker: KakaoMarker,
  isSelected: boolean,
  grade: DefectGrade | null,
): void {
  const color = getGradeColor(grade);
  const image = createMarkerImage(color, isSelected);
  marker.setImage?.(image);
  marker.setZIndex?.(isSelected ? 20 : 1);
}

// InfoWindow content는 innerHTML로 주입되므로 HTML 문자열 대신 DOM 노드를 구성하고,
// 사용자 유래 값(시설물명)은 textContent로만 삽입해 XSS를 차단한다
export function buildInfoWindowContent(facility: FacilityLocation): HTMLElement {
  const container = document.createElement('div');
  container.style.cssText = 'padding:8px 12px;min-width:140px;font-size:13px;';

  const nameEl = document.createElement('strong');
  nameEl.style.cssText = 'display:block;margin-bottom:4px;';
  nameEl.textContent = facility.name;

  const gradeEl = document.createElement('span');
  gradeEl.style.color = getGradeColor(facility.highestGrade);
  gradeEl.style.fontWeight = '600';
  gradeEl.textContent = getGradeLabel(facility.highestGrade);

  container.appendChild(nameEl);
  container.appendChild(gradeEl);
  return container;
}

export function createFacilityMarker(
  map: KakaoMap,
  facility: FacilityLocation,
  onSelect: (facility: FacilityLocation, marker: KakaoMarker) => void,
  isSelected = false,
): KakaoMarker {
  const position = new window.kakao.maps.LatLng(facility.latitude, facility.longitude);
  const color = getGradeColor(facility.highestGrade);
  const image = createMarkerImage(color, isSelected);

  const marker = new window.kakao.maps.Marker({
    position,
    map,
    title: facility.name,
    image,
  });

  if (isSelected) {
    marker.setZIndex?.(20);
  }

  // 마커 인스턴스를 클로저로 캡처해 함께 전달 — 좌표 비교로 마커를 되찾는 검색 불필요
  window.kakao.maps.event.addListener(marker, 'click', () => onSelect(facility, marker));

  return marker;
}
