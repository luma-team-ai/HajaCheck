/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_KAKAO_MAP_APP_KEY: string;
  readonly VITE_ENABLE_MSW?: string;
  // 토스페이먼츠 결제창 연동(#989, HAJA-490) — 클라이언트 키(공개 키, 번들에 노출돼도 안전).
  // 카카오맵 키(#323)와 동일한 함정: Vite 빌드타임 인라인이라 frontend/.env.local(네이티브)과
  // 루트 .env(도커/배포)가 별개 소스 — 배포 전 두 곳 모두 채워야 한다(shared/lib/tossPayments 참고).
  readonly VITE_TOSS_CLIENT_KEY?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
