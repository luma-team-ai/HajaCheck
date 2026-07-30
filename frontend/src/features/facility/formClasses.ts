// 시설물 등록 모달(FacilityFormModal) 재구성(#629)으로 여러 필드 컴포넌트에 분리된 폼 스타일
// 상수를 단일 소스로 승격 — auth/formClasses.ts와 같은 취지, feature 로컬 전용(shared/ 승격은
// React_코드_컨벤션.md §1상 별도 협의 사항이라 이번 범위 밖).
export const LABEL_CLASSES = 'text-sm font-medium text-text-default';

export const INPUT_CLASSES =
  'w-full rounded-lg border border-border bg-surface-muted px-3 py-2 text-sm text-text-default outline-none focus:ring-2 focus:ring-primary';

export const ERROR_CLASSES = 'text-xs text-danger';

// best-effort 경고(예: Geocoder 실패 — 등록 자체는 막지 않음)용 — 필수검증 실패(ERROR_CLASSES,
// danger/빨강)와 시각적으로 구분해 "실패"가 아니라 "경고"임을 알린다. tokens.css의
// --color-warning-soft-fg 재사용(FacilityListPage의 geocodeWarningMessage 배너와 동일 톤).
export const WARNING_CLASSES = 'text-xs text-warning-soft-fg';
