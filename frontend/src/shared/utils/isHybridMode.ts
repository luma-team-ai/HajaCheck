export interface HybridModeEnv {
  VITE_ENABLE_MSW?: string;
}

/** VITE_ENABLE_MSW의 hybrid 모드 여부를 공통으로 판정한다. */
export function isHybridMode(env: HybridModeEnv): boolean {
  return env.VITE_ENABLE_MSW?.trim().toLowerCase() === 'hybrid';
}

export function getEffectiveAuthHandlers<T>(env: HybridModeEnv, handlers: readonly T[]): T[] {
  return isHybridMode(env) ? [] : [...handlers];
}
