// Figma(dev-04-01) 등록 모달 "시설물 유형" 셀렉트 예시 옵션 — 백엔드 type은 자유 문자열(≤20자)이라
// 여기 목록은 UI상 기본 선택지일 뿐 서버 검증값은 아니다.
export const FACILITY_TYPE_OPTIONS = ['건물', '교량', '터널', '도로', '기타'] as const;
