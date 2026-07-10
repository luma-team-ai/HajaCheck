// 지도 뷰 도메인 상수 — 등급별 마커 색상 (하드코딩 산재 방지, 이 파일에서만 정의)
import type { DefectGrade } from './types';

export const GRADE_COLOR: Record<DefectGrade, string> = {
  GREEN: '#22C55E',
  YELLOW: '#EAB308',
  RED: '#EF4444',
};

export const GRADE_LABEL: Record<DefectGrade, string> = {
  GREEN: '양호',
  YELLOW: '주의',
  RED: '심각',
};

/** 미지원/예상치 못한 등급 값에 대한 기본 색상·라벨 (실 API 연동 시 방어용) */
export const FALLBACK_GRADE_COLOR = '#9CA3AF';
export const FALLBACK_GRADE_LABEL = '알 수 없음';

/** 에러 메시지 텍스트 색상 */
export const ERROR_TEXT_COLOR = '#B91C1C';

/** 시설물 목록이 비어있을 때 초기 지도 중심 (서울시청) */
export const DEFAULT_MAP_CENTER = { latitude: 37.5665, longitude: 126.978 };
export const DEFAULT_MAP_LEVEL = 7;
