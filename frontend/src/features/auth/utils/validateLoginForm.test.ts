import { describe, expect, it } from 'vitest';
import { isLoginFormValid } from './validateLoginForm';

describe('isLoginFormValid', () => {
  it('아이디·비밀번호가 모두 있으면 true를 반환한다', () => {
    expect(isLoginFormValid('hajacheck', 'password1234')).toBe(true);
  });

  it('아이디가 빈 값이면 false를 반환한다', () => {
    expect(isLoginFormValid('', 'password1234')).toBe(false);
  });

  it('비밀번호가 빈 값이면 false를 반환한다', () => {
    expect(isLoginFormValid('hajacheck', '')).toBe(false);
  });

  it('공백만 있는 값은 빈 값으로 처리한다', () => {
    expect(isLoginFormValid('   ', '   ')).toBe(false);
  });

  it('둘 다 빈 값이면 false를 반환한다', () => {
    expect(isLoginFormValid('', '')).toBe(false);
  });
});
