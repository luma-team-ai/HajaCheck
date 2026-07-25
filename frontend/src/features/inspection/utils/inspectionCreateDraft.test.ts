// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  clearInspectionCreateDraft,
  loadInspectionCreateDraft,
  saveInspectionCreateDraft,
} from './inspectionCreateDraft';

const DRAFT_KEY = 'hajacheckInspectionCreateDraft';

describe('inspectionCreateDraft', () => {
  afterEach(() => {
    sessionStorage.clear();
    vi.restoreAllMocks();
  });

  it('저장된 초안이 없으면 null을 반환한다', () => {
    expect(loadInspectionCreateDraft()).toBeNull();
  });

  it('저장하면 그대로 조회할 수 있다(라운드트립)', () => {
    saveInspectionCreateDraft({
      facilityId: '1',
      inspectionDate: '2026-08-01',
      memo: '균열 확인 필요',
    });

    expect(loadInspectionCreateDraft()).toEqual({
      facilityId: '1',
      inspectionDate: '2026-08-01',
      memo: '균열 확인 필요',
    });
  });

  it('손상된 JSON이 저장돼 있으면 null을 반환한다', () => {
    sessionStorage.setItem(DRAFT_KEY, '{invalid-json');
    expect(loadInspectionCreateDraft()).toBeNull();
  });

  it('clear 후에는 조회 시 null을 반환한다', () => {
    saveInspectionCreateDraft({ facilityId: '1', inspectionDate: '2026-08-01', memo: '' });
    clearInspectionCreateDraft();
    expect(loadInspectionCreateDraft()).toBeNull();
  });

  it('sessionStorage.setItem이 예외를 던져도 크래시하지 않는다', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('storage blocked');
    });
    expect(() =>
      saveInspectionCreateDraft({ facilityId: '1', inspectionDate: '2026-08-01', memo: '' }),
    ).not.toThrow();
  });

  it('sessionStorage.getItem이 예외를 던져도 크래시 없이 null을 반환한다', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('storage blocked');
    });
    expect(loadInspectionCreateDraft()).toBeNull();
  });

  it('sessionStorage.removeItem이 예외를 던져도 크래시하지 않는다', () => {
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new Error('storage blocked');
    });
    expect(() => clearInspectionCreateDraft()).not.toThrow();
  });
});
