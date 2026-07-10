// 지도 뷰 도메인 타입 — PRD_hajaCheck_v0.37.md 지도 뷰 섹션(92행, 171행) 참고

/** 시설물 하자 최고 등급 — 색상: 녹(GREEN)/황(YELLOW)/적(RED) */
export type DefectGrade = 'GREEN' | 'YELLOW' | 'RED';

/** 지도 마커용 시설물 위치 정보 (EXIF GPS 기반 업로드 좌표) */
export interface FacilityLocation {
  id: number;
  name: string;
  latitude: number;
  longitude: number;
  /** 해당 시설물에 등록된 하자 중 최고 등급 */
  highestGrade: DefectGrade;
}
