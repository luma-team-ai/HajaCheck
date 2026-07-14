// MSW 목서버 구동 여부 판정 — main.tsx에서 분리해 단위 테스트 가능하게 함.
// 기본(DEV && VITE_ENABLE_MSW 미설정/'true')은 켜짐(기존 동작 유지),
// VITE_ENABLE_MSW==='false' 로 로컬 실 백엔드 통합 확인 시에만 끈다.
export interface MockingEnv {
  DEV: boolean;
  VITE_ENABLE_MSW?: string;
}

export function shouldEnableMocking(env: MockingEnv): boolean {
  if (!env.DEV) return false;
  if (env.VITE_ENABLE_MSW === 'false') return false;
  return true;
}
