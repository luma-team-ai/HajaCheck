import { describe, expect, it } from 'vitest';
import { isFindPasswordFormValid } from './validateFindPasswordForm';

describe('isFindPasswordFormValid', () => {
  it('이메일·사업자번호가 모두 유효하면 true를 반환한다', () => {
    expect(isFindPasswordFormValid('haja@check.com', '123-45-67890')).toBe(true);
  });

  it('이메일이 잘못되면 false를 반환한다', () => {
    expect(isFindPasswordFormValid('invalid', '123-45-67890')).toBe(false);
  });

  it('사업자번호가 잘못되면 false를 반환한다', () => {
    expect(isFindPasswordFormValid('haja@check.com', '123')).toBe(false);
  });
});
