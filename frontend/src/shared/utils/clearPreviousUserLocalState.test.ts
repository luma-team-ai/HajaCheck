// @vitest-environment jsdom
// clearPreviousUserLocalState — 6개 진입점(로그인 3곳·useLogout·401 강제 리다이렉트)이 공유하는
// "이전 사용자 잔여 로컬 상태 정리" 계약을 한 곳에서 검증한다(PR #1708 2차 P1). 개별 호출부
// 테스트(useLogin.test.tsx/useLogout.test.tsx/axios.test.ts 등)는 이 헬퍼가 호출됐는지만 고정하고,
// 헬퍼 자신이 실제로 5가지를 전부 정리하는지는 여기서 resetModules 없이(=모듈 재평가로 인한 mock
// 인스턴스 불일치 걱정 없이) 안정적으로 검증한다.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useInspectionStore } from '../../features/inspection/store/inspectionStore';
import {
  loadInspectionCreateDraft,
  saveInspectionCreateDraft,
} from '../../features/inspection/utils/inspectionCreateDraft';
import { clearDraftMediaFiles } from '../../features/inspection/utils/inspectionCreateDraftFiles';
import { getRagSessionId, setRagSessionId } from '../../features/support/utils/ragSessionId';
import { clearPreviousUserLocalState } from './clearPreviousUserLocalState';

// jsdom엔 기본적으로 indexedDB가 없어(fake-indexeddb 전역 폴리필 미설정) 실제 clearDraftMediaFiles
// 구현을 그대로 쓰면 openDb()가 조용히 실패해(자체 try/catch로 삼킴) 호출 여부를 관찰할 수 없다 —
// InspectionCreatePage.test.tsx와 동일한 이유로 이 모듈만 스파이 가능한 목으로 교체한다.
vi.mock('../../features/inspection/utils/inspectionCreateDraftFiles', () => ({
  saveDraftMediaFiles: vi.fn().mockResolvedValue(undefined),
  loadDraftMediaFiles: vi.fn().mockResolvedValue([]),
  clearDraftMediaFiles: vi.fn().mockResolvedValue(undefined),
}));

afterEach(() => {
  localStorage.clear();
  vi.mocked(clearDraftMediaFiles).mockClear();
  useInspectionStore.getState().clearActiveInspectionId();
  useInspectionStore.getState().clearActiveReportId();
});

describe('clearPreviousUserLocalState', () => {
  it('activeInspectionId/activeReportId를 지운다(#1194)', () => {
    useInspectionStore.getState().setActiveInspectionId(42);
    useInspectionStore.getState().setActiveReportId(7);

    clearPreviousUserLocalState();

    expect(useInspectionStore.getState().activeInspectionId).toBeNull();
    expect(useInspectionStore.getState().activeReportId).toBeNull();
  });

  it('RAG 챗봇 session_id를 지운다(#1590)', () => {
    setRagSessionId(77);

    clearPreviousUserLocalState();

    expect(getRagSessionId()).toBeNull();
  });

  it('점검 생성 폼의 localStorage 텍스트 초안을 지운다(#1703)', () => {
    saveInspectionCreateDraft({
      facilityId: '1',
      inspectionDate: '2026-08-01',
      inspectionType: 'DETAILED',
      memo: '이전 사용자가 입력한 메모',
    });
    expect(loadInspectionCreateDraft()).not.toBeNull();

    clearPreviousUserLocalState();

    expect(loadInspectionCreateDraft()).toBeNull();
  });

  it('점검 생성 폼의 IndexedDB 사진 초안 정리도 시도한다(#1703)', () => {
    clearPreviousUserLocalState();

    expect(clearDraftMediaFiles).toHaveBeenCalled();
  });
});
