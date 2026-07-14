// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest';
import { clearSavedLoginId, getSavedLoginId, setSavedLoginId } from './savedLoginId';

describe('savedLoginId', () => {
  afterEach(() => {
    localStorage.clear();
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
});
