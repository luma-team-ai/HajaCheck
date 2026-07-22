// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { InspectionCycleSettingsPage } from './InspectionCycleSettingsPage';
import { resetInspectionCycleStatusMockStore } from '../mocks/inspectionCycle.mock';

const mockSetSchedule = vi.fn();
vi.mock('../hooks/useSetInspectionSchedule', () => ({
  useSetInspectionSchedule: () => ({
    setSchedule: mockSetSchedule,
    isPending: false,
    error: null,
    resetError: vi.fn(),
  }),
}));

vi.mock('../hooks/useInspectionCycleStatusRows', () => ({
  useInspectionCycleStatusRows: () => ({
    data: [
      {
        id: 3,
        name: '오피스타워',
        type: '정기',
        cycleMonths: 6,
        lastInspectedAt: '2026-06-21',
        nextInspectionDueAt: '2026-12-21',
        assigneeName: '박책임',
      },
      {
        id: 4,
        name: '정밀동',
        type: '정밀',
        cycleMonths: 3,
        lastInspectedAt: '2026-05-01',
        nextInspectionDueAt: '2026-12-01',
        assigneeName: '이정밀',
      },
    ],
    isLoading: false,
    isError: false,
  }),
}));

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/facilities/inspection-cycle']}>
      <InspectionCycleSettingsPage />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.stubEnv('VITE_ENABLE_MSW', 'true');
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  vi.unstubAllEnvs();
  resetInspectionCycleStatusMockStore();
});

describe('InspectionCycleSettingsPage', () => {
  it('저장 버튼을 누르면 선택된 시설물 id와 현재 개월 수로 저장 뮤테이션을 호출한다', async () => {
    mockSetSchedule.mockResolvedValue({ nextInspectionDueAt: '2027-01-20' });
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => {
      expect(mockSetSchedule).toHaveBeenCalledWith({
        facilityId: 3,
        body: { inspectionCycleMonths: 6 },
      });
    });
  });

  it('저장 성공 시 응답의 다음 점검일로 화면 표시가 갱신된다', async () => {
    mockSetSchedule.mockResolvedValue({ nextInspectionDueAt: '2027-01-20' });
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    expect(await screen.findByText('2027-01-20')).toBeTruthy();
  });

  it('데모 기준일(2026-11-09) 기준으로 기본 시설물(다음점검 2026-12-21)의 뱃지를 "D-42"로 표시한다', () => {
    // 실제 오늘이 아니라 고정 데모 기준일로 상태를 파생하므로, 154일 뒤라 "여유"로만 보이던 문제 없이
    // Figma 디자인과 동일하게 D-42가 카드·현황 테이블 양쪽에 표시된다.
    renderPage();

    expect(screen.getAllByText('D-42').length).toBeGreaterThanOrEqual(1);
    expect(screen.queryByText('여유')).toBeNull();
  });

  it('+ 스테퍼로 개월 수를 올린 뒤 저장하면 변경된 개월 수로 호출된다', async () => {
    mockSetSchedule.mockResolvedValue({ nextInspectionDueAt: '2027-02-21' });
    renderPage();

    fireEvent.click(screen.getByLabelText('주기 1개월 증가'));
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => {
      expect(mockSetSchedule).toHaveBeenCalledWith({
        facilityId: 3,
        body: { inspectionCycleMonths: 7 },
      });
    });
  });

  it('다른 시설물(정밀) 행을 선택하면 세그먼트 토글이 그 행의 점검유형으로 동기화된다', async () => {
    // #462 P2: cycleType이 항상 '정기'로 초기화되던 문제 — 행 선택 시 실제 type과 맞춰져야 한다.
    renderPage();

    // 초기 선택행(id=3)은 '정기' — 세그먼트의 '정기' 버튼이 눌린 상태여야 한다.
    expect(screen.getByRole('button', { name: '정기' }).getAttribute('aria-pressed')).toBe('true');
    expect(screen.getByRole('button', { name: '정밀' }).getAttribute('aria-pressed')).toBe('false');

    const targetRow = screen.getByText('정밀동').closest('tr');
    expect(targetRow).not.toBeNull();
    fireEvent.click(targetRow as HTMLElement);

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '정밀' }).getAttribute('aria-pressed')).toBe('true');
    });
    expect(screen.getByRole('button', { name: '정기' }).getAttribute('aria-pressed')).toBe('false');
  });

  it('저장 뮤테이션이 실패해도 unhandled rejection 없이 클릭이 정상 처리된다', async () => {
    // #462 P2: handleSave에 try/catch가 없으면 reject 시 onClick 내부에서 unhandled rejection이 발생한다.
    mockSetSchedule.mockRejectedValue(new Error('저장 실패'));
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => {
      expect(mockSetSchedule).toHaveBeenCalledWith({
        facilityId: 3,
        body: { inspectionCycleMonths: 6 },
      });
    });
    // 여기까지 예외 없이 도달하면(reject가 테스트를 깨지 않으면) 통과.
  });
});
