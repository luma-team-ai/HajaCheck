// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  clearSavedLoginId,
  getSavedLoginId,
  resolveSavedLoginId,
  setSavedLoginId,
} from './savedLoginId';

describe('savedLoginId', () => {
  afterEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });

  it('저장된 아이디가 없으면 null을 반환한다', () => {
    expect(getSavedLoginId()).toBeNull();
  });

  it('아이디를 저장하면 조회할 수 있다', () => {
    setSavedLoginId('hajacheck');
    expect(getSavedLoginId()).toBe('hajacheck');
  });

  it('저장된 아이디를 제거하면 다시 null을 반환한다', () => {
    setSavedLoginId('hajacheck');
    clearSavedLoginId();
    expect(getSavedLoginId()).toBeNull();
  });

  it('localStorage.getItem이 예외를 던져도 크래시 없이 null을 반환한다', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('storage blocked');
    });
    expect(getSavedLoginId()).toBeNull();
  });

  it('localStorage.setItem이 예외를 던져도 크래시하지 않는다', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('storage blocked');
    });
    expect(() => setSavedLoginId('hajacheck')).not.toThrow();
  });

  it('localStorage.removeItem이 예외를 던져도 크래시하지 않는다', () => {
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new Error('storage blocked');
    });
    expect(() => clearSavedLoginId()).not.toThrow();
  });
});

describe('resolveSavedLoginId', () => {
  it('체크되어 있고 아이디가 있으면 trim된 아이디를 반환한다', () => {
    expect(resolveSavedLoginId(true, '  hajacheck  ')).toBe('hajacheck');
  });

  it('체크되어 있어도 아이디가 빈 값이면 null을 반환한다', () => {
    expect(resolveSavedLoginId(true, '')).toBeNull();
  });

  it('체크되어 있어도 아이디가 공백만 있으면 null을 반환한다', () => {
    expect(resolveSavedLoginId(true, '   ')).toBeNull();
  });

  it('체크 해제 상태면 아이디가 있어도 null을 반환한다', () => {
    expect(resolveSavedLoginId(false, 'hajacheck')).toBeNull();
  });
});
