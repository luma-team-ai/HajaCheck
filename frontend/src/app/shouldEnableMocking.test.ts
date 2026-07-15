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

  it('false 표기 흔들림(대문자·앞뒤공백)도 꺼진 것으로 취급한다', () => {
    expect(shouldEnableMocking({ DEV: true, VITE_ENABLE_MSW: 'False' })).toBe(false);
    expect(shouldEnableMocking({ DEV: true, VITE_ENABLE_MSW: ' false ' })).toBe(false);
  });

  it("'false' 가 아닌 다른 값('0','no' 등)은 켜진 상태로 남는다(fail-safe)", () => {
    expect(shouldEnableMocking({ DEV: true, VITE_ENABLE_MSW: '0' })).toBe(true);
    expect(shouldEnableMocking({ DEV: true, VITE_ENABLE_MSW: 'no' })).toBe(true);
  });
});
