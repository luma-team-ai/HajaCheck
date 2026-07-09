// VITE_KAKAO_MAP_APP_KEY 타입 보강 — 프로젝트 전역 vite-env.d.ts 가 아직 없어 이 feature 폴더 내에서만 선언
// (React_코드_컨벤션.md §10: import.meta.env.VITE_* 만 사용)

export {};

declare global {
  interface ImportMetaEnv {
    readonly VITE_KAKAO_MAP_APP_KEY?: string;
  }

  interface ImportMeta {
    readonly env: ImportMetaEnv;
  }
}
