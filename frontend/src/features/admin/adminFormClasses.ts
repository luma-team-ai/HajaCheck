// CreateUserModal·RagDocumentUploadForm(#22/HAJA-35)에 문자 그대로 중복돼 있던 입력창 스타일을
// admin feature 전용 단일 소스로 승격(code-review 재사용 지적). auth/formClasses.ts와 동일 원칙 —
// shared/로의 승격은 React_코드_컨벤션.md §1상 Frontend 리드 협의 사항이라 이번 범위 밖, feature
// 내부(admin) 공유까지만 한다.
export const ADMIN_FORM_INPUT_CLASS =
  'w-full rounded-full border border-border bg-surface px-4 py-3 text-sm text-text-default placeholder:text-text-muted focus:outline-none focus-visible:ring-1 focus-visible:ring-primary';

export const ADMIN_FORM_LABEL_CLASS = 'text-xs font-medium tracking-wide text-text-muted';
