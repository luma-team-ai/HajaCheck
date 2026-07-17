import { describe, expect, it } from 'vitest';
import { isResetPasswordFormValid } from './validateResetPasswordForm';

describe('isResetPasswordFormValid', () => {
  it('정책을 만족하는 비밀번호 + 일치하는 확인란이면 true', () => {
    expect(isResetPasswordFormValid('abcd1234', 'abcd1234')).toBe(true);
  });

  it('비밀번호가 정책(8자 이상, 영문+숫자)을 만족하지 않으면 false', () => {
    expect(isResetPasswordFormValid('abcd', 'abcd')).toBe(false);
    expect(isResetPasswordFormValid('abcdefgh', 'abcdefgh')).toBe(false); // 숫자 없음
    expect(isResetPasswordFormValid('12345678', '12345678')).toBe(false); // 영문 없음
  });

  it('확인란이 일치하지 않으면 false', () => {
    expect(isResetPasswordFormValid('abcd1234', 'abcd9999')).toBe(false);
  });

  it('확인란이 비어 있으면 false', () => {
    expect(isResetPasswordFormValid('abcd1234', '')).toBe(false);
  });
});
