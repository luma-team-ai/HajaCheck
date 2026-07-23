// 지도 뷰 도메인 타입 — PRD_hajaCheck_v0.37.md 지도 뷰 섹션(92행, 171행) 참고
// feature 간 직접 import 금지(React_코드_컨벤션.md §1) — dashboard/facility 타입과 별개로 로컬 정의

/**
 * 시설물 하자 최고 등급 — dashboard feature의 등급 체계(A~E)와 동기화된 값(HAJA-150, #129 재오픈).
 * dashboard/utils/gradeDistribution.ts의 GRADE_ORDER, dashboard/colors.ts의 GRADE_BG_CLASS와
 * 라벨/색상 hex(#16a34a/#65a30d/#eab308/#f97316/#dc2626)를 2026-07-16 기준 육안 대조로 확인함
 * (features/map/constants.ts GRADE_COLOR 참고, feature 간 import는 컨벤션상 금지되어 로컬 재정의).
 * 백엔드 계약상의 등급 값이며, mapApi(features/map/api/mapApi.ts)는 현재 목(mock) 데이터 전용이므로
 * 실 API(#8) 연동 시 서버가 내려주는 실제 등급 값 집합이 A~E 5단계와 일치하는지 재확인이 필요하다.
 */
export type DefectGrade = 'A' | 'B' | 'C' | 'D' | 'E';

/** 지도 마커·목록 패널용 시설물 위치 정보 (EXIF GPS 기반 업로드 좌표) */
export interface FacilityLocation {
  id: number;
  name: string;
  address: string;
  /** 시설물 유형 — features/facility Facility.type 필드 참고(로컬 재정의, import 금지) */
  category: string;
  latitude: number;
  longitude: number;
  /**
   * 해당 시설물에 등록된 하자 중 최고 등급.
   * 백엔드 FacilityResponse(backend .../facility/dto/FacilityResponse.java)에는 아직 등급 필드가
   * 없으므로(등급 산정 API 미구현), API 연동 상태에서는 항상 null("등급 미정")이다. 등급 API
   * 연동(#661 범위 밖) 전까지 UI는 null을 정상 상태로 취급해 처리해야 한다.
   */
  highestGrade: DefectGrade | null;
  /** 결함(경고) 건수 — 등급과 동일 사유로 API 미연동 시 null("집계 없음") */
  warningCount: number | null;
  /** 주의 건수 — 등급과 동일 사유로 API 미연동 시 null("집계 없음") */
  cautionCount: number | null;
  thumbnailUrl: string | null;
}
