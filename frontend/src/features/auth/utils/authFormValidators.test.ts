import { describe, expect, it } from 'vitest';
import {
  doPasswordsMatch,
  isValidBusinessNumber,
  isValidEmail,
  isValidPassword,
  normalizeBusinessNumber,
} from './authFormValidators';

describe('isValidEmail', () => {
  it('올바른 이메일 형식이면 true를 반환한다', () => {
    expect(isValidEmail('haja@check.com')).toBe(true);
  });

  it('@가 없으면 false를 반환한다', () => {
    expect(isValidEmail('haja-check.com')).toBe(false);
  });

  it('빈 값이면 false를 반환한다', () => {
    expect(isValidEmail('')).toBe(false);
  });
});

describe('isValidPassword', () => {
  it('8자 이상이고 영문+숫자를 포함하면 true를 반환한다', () => {
    expect(isValidPassword('abcd1234')).toBe(true);
  });

  it('8자 미만이면 false를 반환한다', () => {
    expect(isValidPassword('abc123')).toBe(false);
  });

  it('숫자가 없으면 false를 반환한다', () => {
    expect(isValidPassword('abcdefgh')).toBe(false);
  });

  it('영문이 없으면 false를 반환한다', () => {
    expect(isValidPassword('12345678')).toBe(false);
  });
});

describe('doPasswordsMatch', () => {
  it('두 값이 같으면 true를 반환한다', () => {
    expect(doPasswordsMatch('abcd1234', 'abcd1234')).toBe(true);
  });

  it('두 값이 다르면 false를 반환한다', () => {
    expect(doPasswordsMatch('abcd1234', 'abcd9999')).toBe(false);
  });

  it('둘 다 빈 값이면 false를 반환한다(일치 표시 방지)', () => {
    expect(doPasswordsMatch('', '')).toBe(false);
  });
});

describe('normalizeBusinessNumber / isValidBusinessNumber', () => {
  it('하이픈을 제거한다', () => {
    expect(normalizeBusinessNumber('123-45-67890')).toBe('1234567890');
  });

  it('하이픈 포함 10자리는 유효하다', () => {
    expect(isValidBusinessNumber('123-45-67890')).toBe(true);
  });

  it('하이픈 미포함 10자리도 유효하다', () => {
    expect(isValidBusinessNumber('1234567890')).toBe(true);
  });

  it('10자리가 아니면 무효하다', () => {
    expect(isValidBusinessNumber('123-45-6789')).toBe(false);
  });

  it('숫자가 아닌 문자만 있으면 무효하다', () => {
    expect(isValidBusinessNumber('')).toBe(false);
  });
});
