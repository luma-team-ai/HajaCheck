import { describe, expect, it } from 'vitest';
import { shouldEnableMocking } from './shouldEnableMocking';

describe('shouldEnableMocking', () => {
  it('DEV 아니면 VITE_ENABLE_MSW 설정과 무관하게 꺼진다', () => {
    expect(shouldEnableMocking({ DEV: false })).toBe(false);
    expect(shouldEnableMocking({ DEV: false, VITE_ENABLE_MSW: 'true' })).toBe(false);
  });

  it('DEV + VITE_ENABLE_MSW 미설정이면 기본값(켜짐)을 유지한다', () => {
    expect(shouldEnableMocking({ DEV: true })).toBe(true);
  });

  it('DEV + VITE_ENABLE_MSW=true 면 켜진다', () => {
    expect(shouldEnableMocking({ DEV: true, VITE_ENABLE_MSW: 'true' })).toBe(true);
  });

  it('DEV + VITE_ENABLE_MSW=false 면 꺼진다(실 백엔드 통합 테스트 모드)', () => {
    expect(shouldEnableMocking({ DEV: true, VITE_ENABLE_MSW: 'false' })).toBe(false);
  });
});
