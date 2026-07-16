// 기업 회원가입(#292) Tailwind 전환 시 CompanySignupPage·CompanyAddressField·BusinessLicenseUpload
// 세 파일에 거의 동일하게 중복되던 클래스 상수를 단일 소스로 승격(PR머신 P3).
// feature 밖(shared/)으로의 승격은 하지 않는다 — auth feature 전용 폼 스타일이고, shared 승격은
// React_코드_컨벤션.md §1상 Frontend 리드 협의 사항이라 이번 범위 밖.
export const LABEL_CLASSES = 'text-sm font-medium text-text-default';

export const INPUT_CLASSES =
  'w-full rounded-lg border border-border bg-surface-muted px-3.5 py-3 text-sm text-text-default outline-none focus:ring-2 focus:ring-primary';

export const ERROR_CLASSES = 'text-xs text-danger';

// auth.css의 기존 .auth-form-success(#1a9a52)와 동일 값을 그대로 이식 — 신규 색이 아니라 기존
// 성공색을 재사용하는 것. tokens.css는 타 오너 자산이라 미터치 규칙상 토큰 승격 대신 로컬 상수로
// 유지(후속 이슈로 tokens.css 승격 여부는 Frontend 리드와 별도 협의 — P3).
export const SUCCESS_CLASSES = 'text-xs text-[#1a9a52]';
