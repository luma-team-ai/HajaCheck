import { describe, expect, it } from 'vitest';
import { getEffectiveAuthHandlers, isHybridMode } from './isHybridMode';

describe('isHybridMode', () => {
  it('hybrid 문자열은 대소문자와 앞뒤 공백을 무시하고 인식한다', () => {
    expect(isHybridMode({ VITE_ENABLE_MSW: 'hybrid' })).toBe(true);
    expect(isHybridMode({ VITE_ENABLE_MSW: ' Hybrid ' })).toBe(true);
    expect(isHybridMode({ VITE_ENABLE_MSW: 'HYBRID' })).toBe(true);
  });

  it('hybrid에서는 auth 핸들러를 등록하지 않는다', () => {
    const authHandlers = ['login', 'me'];
    expect(getEffectiveAuthHandlers({ VITE_ENABLE_MSW: ' hybrid ' }, authHandlers)).toEqual([]);
  });

  it('hybrid가 아닌 값은 인증 MSW를 끄는 모드로 판정하지 않는다', () => {
    expect(isHybridMode({ VITE_ENABLE_MSW: 'true' })).toBe(false);
    expect(isHybridMode({ VITE_ENABLE_MSW: 'false' })).toBe(false);
    expect(isHybridMode({ VITE_ENABLE_MSW: '0' })).toBe(false);
    expect(isHybridMode({})).toBe(false);
    const authHandlers = ['login', 'me'];
    expect(getEffectiveAuthHandlers({ VITE_ENABLE_MSW: 'true' }, authHandlers)).toEqual(authHandlers);
    expect(getEffectiveAuthHandlers({}, authHandlers)).toEqual(authHandlers);
  });
});
