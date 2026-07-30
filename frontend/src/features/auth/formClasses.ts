// 기업 회원가입(#292) Tailwind 전환 시 CompanySignupPage·CompanyAddressField·BusinessLicenseUpload
// 세 파일에 거의 동일하게 중복되던 클래스 상수를 단일 소스로 승격(PR머신 P3).
// feature 밖(shared/)으로의 승격은 하지 않는다 — auth feature 전용 폼 스타일이고, shared 승격은
// React_코드_컨벤션.md §1상 Frontend 리드 협의 사항이라 이번 범위 밖.
export const LABEL_CLASSES = 'text-sm font-medium text-text-default';

export const INPUT_CLASSES =
  'w-full rounded-lg border border-border bg-surface-muted px-3.5 py-3 text-sm text-text-default outline-none focus:ring-2 focus:ring-primary';

// 로그인 화면(#295) 전용 입력창 — 기업 회원가입 시안(Figma node 50-63 계열, INPUT_CLASSES)과
// 로그인 시안(Figma node 53-390)이 실제로 다르게 그려져 있어 공유하지 않고 분리한다:
// 모서리 rounded-lg(8px)→rounded-xl(12px), 배경 bg-surface-muted(#fafafa)→bg-surface(흰색),
// 글자 text-sm(14px)→text-[15px](Figma 실측 15px, Tailwind 기본 스케일에 없어 임의값 사용).
// 색은 raw zinc-*를 그대로 쓰지 않고 토큰 매핑: bg-white→bg-surface, border-zinc-200→border-border,
// text-zinc-900(#18181b)→text-primary(#18181b, 정확히 일치하는 토큰), placeholder:text-zinc-400→
// placeholder:text-text-subtle(가장 가까운 토큰, 정확한 hex 대응 토큰 없음).
// 높이 h-[46px]/py-[13px]는 Figma export 산물이라 그대로 쓰지 않고 py-3(12px)로 근사 —
// text-[15px]와 결합하면 실측 46px에 근접한다. INPUT_CLASSES는 회원가입(#292 머지분)이 계속 쓰므로
// 절대 수정하지 말 것(수정 시 CompanySignupPage 시각 회귀).
export const LOGIN_INPUT_CLASSES =
  'w-full rounded-xl border border-border bg-surface px-4 py-3 text-[15px] text-primary outline-none placeholder:text-text-subtle focus:ring-2 focus:ring-primary';

export const ERROR_CLASSES = 'text-xs text-danger';

// 비밀번호 입력창 우측 내부 눈 아이콘 토글 버튼 — CompanySignupPage에 이어 로그인(#295)에서도
// 동일 패턴이 필요해져 단일 소스로 승격(formClasses.ts 취지와 동일하게 중복 정의 방지).
export const PASSWORD_TOGGLE_CLASSES =
  'absolute right-2.5 cursor-pointer border-none bg-transparent p-1.5 text-base leading-none';

// auth.css의 기존 .auth-form-success(#1a9a52)와 동일 값을 그대로 이식 — 신규 색이 아니라 기존
// 성공색을 재사용하는 것. tokens.css는 타 오너 자산이라 미터치 규칙상 토큰 승격 대신 로컬 상수로
// 유지(후속 이슈로 tokens.css 승격 여부는 Frontend 리드와 별도 협의 — P3).
export const SUCCESS_CLASSES = 'text-xs text-[#1a9a52]';

// 사업자등록증 OCR 결과 피드백(#748) — 성공은 기존 SUCCESS_CLASSES를 재사용한다. 인식된 값이
// 0개인 경우는 실패가 아니므로 중립 회색, 실패는 폼 제출을 막지 않는 보조 안내라 danger(빨강)
// 대신 CompanySignupPage의 UNAVAILABLE 뱃지와 동일한 warning 톤을 쓴다(과도한 위험 신호 방지).
export const OCR_FEEDBACK_NEUTRAL_CLASSES = 'text-xs text-text-muted';
export const OCR_FEEDBACK_WARNING_CLASSES = 'text-xs text-warning-soft-fg';

// 자동채움 필드 배지(#748) — CompanySignupPage가 OCR로 실제 채운 필드의 <label> 옆에 표시.
export const AUTO_FILLED_BADGE_CLASSES =
  'ml-1.5 inline-flex items-center rounded-full bg-primary/10 px-2 py-0.5 text-[11px] font-medium text-primary';
