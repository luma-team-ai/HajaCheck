import { describe, expect, it } from 'vitest';
import {
  doPasswordsMatch,
  getPasswordStrength,
  isValidBusinessNumber,
  isValidBusinessStartDate,
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

describe('getPasswordStrength', () => {
  it('빈 값이면 null을 반환한다(미표시)', () => {
    expect(getPasswordStrength('')).toBeNull();
  });

  it('최소 요건(8자+영문+숫자) 미충족이면 weak를 반환한다', () => {
    expect(getPasswordStrength('abc123')).toBe('weak'); // 8자 미만
    expect(getPasswordStrength('abcdefgh')).toBe('weak'); // 숫자 없음
    expect(getPasswordStrength('12345678')).toBe('weak'); // 영문 없음
  });

  it('최소 요건 충족·특수문자 없음·12자 미만이면 medium을 반환한다', () => {
    expect(getPasswordStrength('abcd1234')).toBe('medium');
  });

  it('12자 이상이면 strong을 반환한다', () => {
    expect(getPasswordStrength('abcdefgh1234')).toBe('strong');
  });

  it('특수문자를 포함하면 strong을 반환한다', () => {
    expect(getPasswordStrength('abcd123!')).toBe('strong');
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

describe('isValidBusinessStartDate', () => {
  it('빈 값이면 false를 반환한다', () => {
    expect(isValidBusinessStartDate('')).toBe(false);
  });

  it('과거 날짜면 true를 반환한다', () => {
    expect(isValidBusinessStartDate('2015-03-02')).toBe(true);
  });

  it('오늘 날짜면 true를 반환한다', () => {
    const today = new Date().toISOString().slice(0, 10);
    expect(isValidBusinessStartDate(today)).toBe(true);
  });

  it('미래 날짜면 false를 반환한다', () => {
    const future = new Date();
    future.setFullYear(future.getFullYear() + 1);
    expect(isValidBusinessStartDate(future.toISOString().slice(0, 10))).toBe(false);
  });

  it('형식이 잘못된 날짜면 false를 반환한다', () => {
    expect(isValidBusinessStartDate('not-a-date')).toBe(false);
  });
});
