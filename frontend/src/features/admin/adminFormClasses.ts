// CreateUserModal·RagDocumentUploadForm(#22/HAJA-35)에 문자 그대로 중복돼 있던 입력창 스타일을
// admin feature 전용 단일 소스로 승격(code-review 재사용 지적). auth/formClasses.ts와 동일 원칙 —
// shared/로의 승격은 React_코드_컨벤션.md §1상 Frontend 리드 협의 사항이라 이번 범위 밖, feature
// 내부(admin) 공유까지만 한다.
export const ADMIN_FORM_INPUT_CLASS =
  'w-full rounded-full border border-border bg-surface px-4 py-3 text-sm text-text-default placeholder:text-text-muted focus:outline-none focus-visible:ring-1 focus-visible:ring-primary';

export const ADMIN_FORM_LABEL_CLASS = 'text-xs font-medium tracking-wide text-text-muted';

// 이메일 중복확인(CreateUserModal, admin·platform-admin 공용 패턴 — CompanySignupPage와 동일 UX를
// 관리자 사용자 등록 모달에도 적용) — auth/formClasses.ts의 INLINE_BTN_CLASSES/SUCCESS_CLASSES와
// 같은 값이지만, feature 경계상 admin 쪽에 별도로 둔다(auth 전용 스타일이라 승격하지 않는 원칙 유지).
export const ADMIN_FORM_INLINE_BTN_CLASS =
  'cursor-pointer self-start border-none bg-none p-0 text-xs font-medium text-primary underline disabled:cursor-not-allowed disabled:opacity-50';

export const ADMIN_FORM_ERROR_CLASS = 'm-0 text-xs text-danger';

export const ADMIN_FORM_SUCCESS_CLASS = 'm-0 text-xs text-[#1a9a52]';
