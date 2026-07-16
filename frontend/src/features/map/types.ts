// 지도 뷰 도메인 타입 — PRD_hajaCheck_v0.37.md 지도 뷰 섹션(92행, 171행) 참고
// feature 간 직접 import 금지(React_코드_컨벤션.md §1) — dashboard/facility 타입과 별개로 로컬 정의

/** 시설물 하자 최고 등급 — dashboard와 동일한 5단계 A(양호)~E(중대) 체계(HAJA-150, #129 재오픈) */
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
  /** 해당 시설물에 등록된 하자 중 최고 등급 */
  highestGrade: DefectGrade;
  /** 결함(경고) 건수 */
  warningCount: number;
  /** 주의 건수 */
  cautionCount: number;
  thumbnailUrl: string | null;
}
