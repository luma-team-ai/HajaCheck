// @vitest-environment jsdom
// window.location.origin 기반 URL 정규화를 검증하므로 jsdom 환경 필요
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
    expect(isSafeInternalPath('/\\/evil.com')).toBe(false);
  });

  it('탭·개행 삽입으로 //를 위장해도 거부한다(URL 파서가 제어문자 제거 → cross-origin)', () => {
    expect(isSafeInternalPath('/\t/evil.com')).toBe(false);
    expect(isSafeInternalPath('/\n/evil.com')).toBe(false);
    expect(isSafeInternalPath('/\r/evil.com')).toBe(false);
  });

  it('쿼리스트링·해시가 붙은 내부 경로는 보존하며 허용한다', () => {
    expect(isSafeInternalPath('/dashboard?tab=1#top')).toBe(true);
    expect(isSafeInternalPath('/defects/1?highlight=a')).toBe(true);
  });

  it('%2F 인코딩은 경로 구분자로 해석되지 않아 동일 오리진(내부)으로 허용된다', () => {
    // %2F는 브라우저가 경로 구분자로 취급하지 않음 → 여전히 우리 오리진 내부 경로(외부 이동 아님)
    expect(isSafeInternalPath('/%2F%2Fevil.com')).toBe(true);
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
