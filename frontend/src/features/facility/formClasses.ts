// 시설물 등록 모달(FacilityFormModal) 재구성(#629)으로 여러 필드 컴포넌트에 분리된 폼 스타일
// 상수를 단일 소스로 승격 — auth/formClasses.ts와 같은 취지, feature 로컬 전용(shared/ 승격은
// React_코드_컨벤션.md §1상 별도 협의 사항이라 이번 범위 밖).
export const LABEL_CLASSES = 'text-sm font-medium text-text-default';

export const INPUT_CLASSES =
  'w-full rounded-lg border border-border bg-surface-muted px-3 py-2 text-sm text-text-default outline-none focus:ring-2 focus:ring-primary';

export const ERROR_CLASSES = 'text-xs text-danger';
