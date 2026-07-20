// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { InspectionCycleSettingsPage } from './InspectionCycleSettingsPage';

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

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
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
});
