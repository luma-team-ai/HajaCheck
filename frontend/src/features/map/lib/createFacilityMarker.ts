// 시설물 마커 생성 — 하자 최고 등급별 색상(GRADE_COLOR) 원형 SVG 마커
import { GRADE_COLOR, GRADE_LABEL } from '../constants';
import type { FacilityLocation } from '../types';

function buildMarkerImageSrc(color: string): string {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="28" height="28">
    <circle cx="14" cy="14" r="11" fill="${color}" stroke="#ffffff" stroke-width="3" />
  </svg>`;
  return `data:image/svg+xml;base64,${btoa(svg)}`;
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
  gradeEl.style.color = GRADE_COLOR[facility.highestGrade];
  gradeEl.style.fontWeight = '600';
  gradeEl.textContent = GRADE_LABEL[facility.highestGrade];

  container.appendChild(nameEl);
  container.appendChild(gradeEl);
  return container;
}

export function createFacilityMarker(
  map: KakaoMap,
  facility: FacilityLocation,
  onSelect: (facility: FacilityLocation, marker: KakaoMarker) => void,
): KakaoMarker {
  const position = new window.kakao.maps.LatLng(facility.latitude, facility.longitude);
  const color = GRADE_COLOR[facility.highestGrade];
  const image = new window.kakao.maps.MarkerImage(
    buildMarkerImageSrc(color),
    new window.kakao.maps.Size(28, 28),
  );

  const marker = new window.kakao.maps.Marker({
    position,
    map,
    title: facility.name,
    image,
  });

  // 마커 인스턴스를 클로저로 캡처해 함께 전달 — 좌표 비교로 마커를 되찾는 검색 불필요
  window.kakao.maps.event.addListener(marker, 'click', () => onSelect(facility, marker));

  return marker;
}
