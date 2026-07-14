import { describe, expect, it } from 'vitest';
import { isFindIdFormValid } from './validateFindIdForm';

describe('isFindIdFormValid', () => {
  it('사업자번호+상호명이면 true를 반환한다', () => {
    expect(isFindIdFormValid('123-45-67890', '(주)하자체크', '')).toBe(true);
  });

  it('사업자번호+대표자명이면 true를 반환한다', () => {
    expect(isFindIdFormValid('123-45-67890', '', '김민수')).toBe(true);
  });

  it('사업자번호만 있고 상호명·대표자명이 모두 없으면 false를 반환한다', () => {
    expect(isFindIdFormValid('123-45-67890', '', '')).toBe(false);
  });

  it('사업자번호 형식이 잘못되면 false를 반환한다', () => {
    expect(isFindIdFormValid('123', '(주)하자체크', '')).toBe(false);
  });
});
