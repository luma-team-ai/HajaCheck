import { describe, expect, it } from 'vitest';
import { isResetPasswordFormValid } from './validateResetPasswordForm';

describe('isResetPasswordFormValid', () => {
  it('8자 이상 영문+숫자 비밀번호가 일치하면 true를 반환한다', () => {
    expect(isResetPasswordFormValid('abcd1234', 'abcd1234')).toBe(true);
  });

  it('비밀번호가 일치하지 않으면 false를 반환한다', () => {
    expect(isResetPasswordFormValid('abcd1234', 'abcd9999')).toBe(false);
  });

  it('비밀번호가 형식에 맞지 않으면 false를 반환한다', () => {
    expect(isResetPasswordFormValid('abc', 'abc')).toBe(false);
  });
});
