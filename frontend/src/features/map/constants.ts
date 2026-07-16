// 지도 뷰 도메인 상수 — 등급별 마커 색상 (하드코딩 산재 방지, 이 파일에서만 정의)
import type { DefectGrade } from './types';

// dashboard/utils/gradeDistribution.ts와 동일한 hex — feature 간 직접 import는 금지이므로 값만 참고해 재정의(HAJA-150, #129)
export const GRADE_COLOR: Record<DefectGrade, string> = {
  A: '#16a34a',
  B: '#65a30d',
  C: '#eab308',
  D: '#f97316',
  E: '#dc2626',
};

export const GRADE_LABEL: Record<DefectGrade, string> = {
  A: '양호',
  B: '경미',
  C: '주의',
  D: '경고',
  E: '중대',
};

/** 미지원/예상치 못한 등급 값에 대한 기본 색상·라벨 (실 API 연동 시 방어용) */
export const FALLBACK_GRADE_COLOR = '#9CA3AF';
export const FALLBACK_GRADE_LABEL = '알 수 없음';

/** 에러 메시지 텍스트 색상 */
export const ERROR_TEXT_COLOR = '#B91C1C';

/** 시설물 목록이 비어있을 때 초기 지도 중심 (서울시청) */
export const DEFAULT_MAP_CENTER = { latitude: 37.5665, longitude: 126.978 };
export const DEFAULT_MAP_LEVEL = 7;
/** Kakao Map level: 숫자가 작을수록 확대. 확대/축소 버튼의 클램프 범위 */
export const MIN_MAP_LEVEL = 1;
export const MAX_MAP_LEVEL = 14;

/**
 * 시설물 목록 패널 카테고리 필터 탭 — features/facility/constants.ts FACILITY_TYPE_OPTIONS 참고
 * (feature 간 직접 import 금지 — 로컬 재정의). '전체'는 필터 미적용을 의미.
 */
export const FACILITY_CATEGORY_FILTERS = ['전체', '건물', '교량', '터널', '도로', '기타'] as const;
