// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { getCompanySignupSession, saveCompanySignupSession } from './companySignupSession';

describe('companySignupSession', () => {
  afterEach(() => {
    sessionStorage.clear();
    vi.restoreAllMocks();
  });

  it('저장된 세션이 없으면 null을 반환한다', () => {
    expect(getCompanySignupSession()).toBeNull();
  });

  it('세션을 저장하면 그대로 조회할 수 있다', () => {
    saveCompanySignupSession({
      signupToken: 'mock-signup-token',
      companyName: '(주)하자체크',
      maskedEmail: 'haja***@check.com',
    });

    expect(getCompanySignupSession()).toEqual({
      signupToken: 'mock-signup-token',
      companyName: '(주)하자체크',
      maskedEmail: 'haja***@check.com',
    });
  });

  it('손상된 JSON이 저장돼 있으면 null을 반환한다', () => {
    sessionStorage.setItem('hajacheckCompanySignupSession', '{invalid-json');
    expect(getCompanySignupSession()).toBeNull();
  });

  it('signupToken이 없는 값이 저장돼 있으면 null을 반환한다', () => {
    sessionStorage.setItem('hajacheckCompanySignupSession', JSON.stringify({ companyName: 'x' }));
    expect(getCompanySignupSession()).toBeNull();
  });

  it('sessionStorage.setItem이 예외를 던져도 크래시하지 않는다', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('storage blocked');
    });
    expect(() =>
      saveCompanySignupSession({
        signupToken: 't',
        companyName: 'c',
        maskedEmail: 'm',
      }),
    ).not.toThrow();
  });

  it('sessionStorage.getItem이 예외를 던져도 크래시 없이 null을 반환한다', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('storage blocked');
    });
    expect(getCompanySignupSession()).toBeNull();
  });
});
