import { describe, expect, it } from 'vitest';
import { isSafeInternalPath } from './safeInternalPath';

describe('isSafeInternalPath', () => {
  it('내부 절대경로는 허용한다', () => {
    expect(isSafeInternalPath('/dashboard')).toBe(true);
    expect(isSafeInternalPath('/defects/1')).toBe(true);
  });

  it('프로토콜-상대 경로(//)는 거부한다', () => {
    expect(isSafeInternalPath('//evil.com')).toBe(false);
  });

  it('백슬래시 트릭(/\\)은 거부한다', () => {
    expect(isSafeInternalPath('/\\evil.com')).toBe(false);
  });

  it('절대 URL(http://, https://)은 거부한다', () => {
    expect(isSafeInternalPath('http://evil.com')).toBe(false);
    expect(isSafeInternalPath('https://evil.com')).toBe(false);
  });

  it('undefined·빈 문자열·비문자열 값은 거부한다', () => {
    expect(isSafeInternalPath(undefined)).toBe(false);
    expect(isSafeInternalPath('')).toBe(false);
    expect(isSafeInternalPath(null)).toBe(false);
    expect(isSafeInternalPath(123)).toBe(false);
  });

  it("'/'로 시작하지 않는 상대경로는 거부한다", () => {
    expect(isSafeInternalPath('dashboard')).toBe(false);
  });
});
