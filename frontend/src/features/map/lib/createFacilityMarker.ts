// 시설물 마커 생성 — 하자 최고 등급별 색상(GRADE_COLOR) 원형 SVG 마커
import { GRADE_COLOR, GRADE_LABEL } from '../constants';
import type { FacilityLocation } from '../types';

function buildMarkerImageSrc(color: string): string {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="28" height="28">
    <circle cx="14" cy="14" r="11" fill="${color}" stroke="#ffffff" stroke-width="3" />
  </svg>`;
  return `data:image/svg+xml;base64,${btoa(svg)}`;
}

export function buildInfoWindowContent(facility: FacilityLocation): string {
  const label = GRADE_LABEL[facility.highestGrade];
  const color = GRADE_COLOR[facility.highestGrade];
  return `
    <div style="padding:8px 12px;min-width:140px;font-size:13px;">
      <strong style="display:block;margin-bottom:4px;">${facility.name}</strong>
      <span style="color:${color};font-weight:600;">${label}</span>
    </div>
  `;
}

export function createFacilityMarker(
  map: KakaoMap,
  facility: FacilityLocation,
  onSelect: (facility: FacilityLocation) => void,
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

  window.kakao.maps.event.addListener(marker, 'click', () => onSelect(facility));

  return marker;
}
