// MSW 목서버 구동 여부 판정 — main.tsx에서 분리해 단위 테스트 가능하게 함.
// 기본(DEV && VITE_ENABLE_MSW 미설정/'true')은 켜짐(기존 동작 유지),
// VITE_ENABLE_MSW 를 'false'(대소문자·앞뒤공백 무시)로 두면 로컬 실 백엔드 통합 확인 시 끈다.
export interface MockingEnv {
  DEV: boolean;
  VITE_ENABLE_MSW?: string;
}

export function shouldEnableMocking(env: MockingEnv): boolean {
  if (!env.DEV) return false;
  // 'False'·' false ' 같은 표기 흔들림도 off 로 취급(가이드 FAQ의 "껐는데 목이 계속 뜸" 혼란 방지).
  if (env.VITE_ENABLE_MSW?.trim().toLowerCase() === 'false') return false;
  return true;
}
