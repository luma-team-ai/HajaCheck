// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  clearInspectionCreateDraft,
  loadInspectionCreateDraft,
  saveInspectionCreateDraft,
} from './inspectionCreateDraft';

const DRAFT_KEY = 'hajacheckInspectionCreateDraft';
const ONE_DAY_MS = 24 * 60 * 60 * 1000;

describe('inspectionCreateDraft', () => {
  afterEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });

  it('저장된 초안이 없으면 null을 반환한다', () => {
    expect(loadInspectionCreateDraft()).toBeNull();
  });

  it('저장하면 그대로 조회할 수 있다(라운드트립)', () => {
    saveInspectionCreateDraft({
      facilityId: '1',
      inspectionDate: '2026-08-01',
      inspectionType: 'DETAILED',
      memo: '균열 확인 필요',
    });

    expect(loadInspectionCreateDraft()).toEqual({
      facilityId: '1',
      inspectionDate: '2026-08-01',
      inspectionType: 'DETAILED',
      memo: '균열 확인 필요',
    });
  });

  it('localStorage에 저장한다(탭·브라우저 재시작 후에도 남아 IndexedDB 사진 초안과 수명이 맞는다)', () => {
    saveInspectionCreateDraft({
      facilityId: '1',
      inspectionDate: '2026-08-01',
      inspectionType: 'REGULAR',
      memo: '',
    });

    expect(localStorage.getItem(DRAFT_KEY)).not.toBeNull();
    expect(sessionStorage.getItem(DRAFT_KEY)).toBeNull();
  });

  it('손상된 JSON이 저장돼 있으면 null을 반환한다', () => {
    localStorage.setItem(DRAFT_KEY, '{invalid-json');
    expect(loadInspectionCreateDraft()).toBeNull();
  });

  it('clear 후에는 조회 시 null을 반환한다', () => {
    saveInspectionCreateDraft({
      facilityId: '1',
      inspectionDate: '2026-08-01',
      inspectionType: 'REGULAR',
      memo: '',
    });
    clearInspectionCreateDraft();
    expect(loadInspectionCreateDraft()).toBeNull();
  });

  it('TTL(7일) 이내에 저장된 초안은 정상 복원된다', () => {
    const oneDayAgo = Date.now() - ONE_DAY_MS;
    localStorage.setItem(
      DRAFT_KEY,
      JSON.stringify({
        facilityId: '1',
        inspectionDate: '2026-08-01',
        inspectionType: 'REGULAR',
        memo: '아직 유효',
        savedAt: oneDayAgo,
      }),
    );

    expect(loadInspectionCreateDraft()).toEqual({
      facilityId: '1',
      inspectionDate: '2026-08-01',
      inspectionType: 'REGULAR',
      memo: '아직 유효',
    });
  });

  it('TTL(7일)이 지난 초안은 복원하지 않고 null을 반환한다', () => {
    const eightDaysAgo = Date.now() - 8 * ONE_DAY_MS;
    localStorage.setItem(
      DRAFT_KEY,
      JSON.stringify({
        facilityId: '1',
        inspectionDate: '2026-08-01',
        inspectionType: 'REGULAR',
        memo: '만료됨',
        savedAt: eightDaysAgo,
      }),
    );

    expect(loadInspectionCreateDraft()).toBeNull();
  });

  it('TTL이 지난 초안을 감지하면 localStorage 레코드 자체도 정리한다(방치 방지)', () => {
    const eightDaysAgo = Date.now() - 8 * ONE_DAY_MS;
    localStorage.setItem(
      DRAFT_KEY,
      JSON.stringify({
        facilityId: '1',
        inspectionDate: '2026-08-01',
        inspectionType: 'REGULAR',
        memo: '',
        savedAt: eightDaysAgo,
      }),
    );

    loadInspectionCreateDraft();

    expect(localStorage.getItem(DRAFT_KEY)).toBeNull();
  });

  it('savedAt 없는 옛 포맷(TTL 도입 이전 저장분)은 무한 유효로 두지 않고 만료로 간주해 폐기한다', () => {
    localStorage.setItem(
      DRAFT_KEY,
      JSON.stringify({
        facilityId: '1',
        inspectionDate: '2026-08-01',
        inspectionType: 'REGULAR',
        memo: '',
      }),
    );

    expect(loadInspectionCreateDraft()).toBeNull();
    expect(localStorage.getItem(DRAFT_KEY)).toBeNull();
  });

  it('localStorage.setItem이 예외를 던져도 크래시하지 않는다', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('storage blocked');
    });
    expect(() =>
      saveInspectionCreateDraft({
        facilityId: '1',
        inspectionDate: '2026-08-01',
        inspectionType: 'REGULAR',
        memo: '',
      }),
    ).not.toThrow();
  });

  it('localStorage.getItem이 예외를 던져도 크래시 없이 null을 반환한다', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('storage blocked');
    });
    expect(loadInspectionCreateDraft()).toBeNull();
  });

  it('localStorage.removeItem이 예외를 던져도 크래시하지 않는다', () => {
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new Error('storage blocked');
    });
    expect(() => clearInspectionCreateDraft()).not.toThrow();
  });
});
