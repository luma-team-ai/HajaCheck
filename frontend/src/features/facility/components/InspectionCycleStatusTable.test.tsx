// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { InspectionCycleStatusTable } from './InspectionCycleStatusTable';
import type { InspectionCycleStatusRow } from '../types';

const mockUseInspectionCycleStatusRows = vi.fn();
vi.mock('../hooks/useInspectionCycleStatusRows', () => ({
  useInspectionCycleStatusRows: () => mockUseInspectionCycleStatusRows(),
}));

// 기준일을 2026-07-20으로 고정 — B1(9/10)=여유이내, 1F(9/25)=여유(안전) 등 상태가 일정하게 파생되도록.
const TODAY = new Date(2026, 6, 20);

const SAMPLE: InspectionCycleStatusRow[] = [
  {
    id: 1,
    name: 'B1 발전기실',
    type: '정기',
    cycleMonths: 3,
    lastInspectedAt: '2026-06-10',
    nextInspectionDueAt: '2026-07-17', // TODAY 기준 D+3 → 초과
    assigneeName: '김관리',
  },
  {
    id: 2,
    name: '1F 메인 로비',
    type: '정밀',
    cycleMonths: 6,
    lastInspectedAt: '2026-03-25',
    nextInspectionDueAt: '2026-07-25', // D-5 → 임박
    assigneeName: '이담당',
  },
  {
    id: 3,
    name: '오피스타워',
    type: '정기',
    cycleMonths: 6,
    lastInspectedAt: '2026-06-21',
    nextInspectionDueAt: '2026-12-21', // 여유
    assigneeName: '박책임',
  },
];

function mockRows(data: InspectionCycleStatusRow[]) {
  mockUseInspectionCycleStatusRows.mockReturnValue({ data, isLoading: false, isError: false });
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(TODAY);
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  vi.useRealTimers();
});

describe('InspectionCycleStatusTable', () => {
  it('데이터가 있으면 7컬럼 헤더와 각 행을 렌더링한다', () => {
    mockRows(SAMPLE);
    render(<InspectionCycleStatusTable selectedId={null} onSelectRow={vi.fn()} />);

    const table = screen.getByRole('table', { name: '전체 시설물 점검 주기 현황' });
    const headers = within(table).getAllByRole('columnheader');
    expect(headers).toHaveLength(7);

    expect(screen.getByText('B1 발전기실')).toBeTruthy();
    expect(screen.getByText('1F 메인 로비')).toBeTruthy();
    expect(screen.getByText('오피스타워')).toBeTruthy();
  });

  it('"임박" 필터를 누르면 임박 상태 행만 남는다', () => {
    mockRows(SAMPLE);
    render(<InspectionCycleStatusTable selectedId={null} onSelectRow={vi.fn()} />);

    fireEvent.click(screen.getByRole('button', { name: '임박' }));

    expect(screen.getByText('1F 메인 로비')).toBeTruthy();
    expect(screen.queryByText('B1 발전기실')).toBeNull();
    expect(screen.queryByText('오피스타워')).toBeNull();
  });

  it('"초과" 필터를 누르면 초과 상태 행만 남는다', () => {
    mockRows(SAMPLE);
    render(<InspectionCycleStatusTable selectedId={null} onSelectRow={vi.fn()} />);

    fireEvent.click(screen.getByRole('button', { name: '초과' }));

    expect(screen.getByText('B1 발전기실')).toBeTruthy();
    expect(screen.queryByText('1F 메인 로비')).toBeNull();
    expect(screen.queryByText('오피스타워')).toBeNull();
  });

  it('행을 클릭하면 onSelectRow가 해당 행 데이터로 호출된다', () => {
    mockRows(SAMPLE);
    const onSelectRow = vi.fn();
    render(<InspectionCycleStatusTable selectedId={null} onSelectRow={onSelectRow} />);

    fireEvent.click(screen.getByText('오피스타워'));

    expect(onSelectRow).toHaveBeenCalledWith(SAMPLE[2]);
  });

  it('선택된 행은 aria-selected=true이다', () => {
    mockRows(SAMPLE);
    render(<InspectionCycleStatusTable selectedId={3} onSelectRow={vi.fn()} />);

    const row = screen.getByText('오피스타워').closest('tr');
    expect(row?.getAttribute('aria-selected')).toBe('true');
  });

  it('데이터가 비어 있으면 안내 문구를 렌더링한다', () => {
    mockRows([]);
    render(<InspectionCycleStatusTable selectedId={null} onSelectRow={vi.fn()} />);

    expect(screen.getByText('해당 상태의 시설물이 없습니다.')).toBeTruthy();
  });

  it('로딩 중이면 로딩 문구를 렌더링한다', () => {
    mockUseInspectionCycleStatusRows.mockReturnValue({ data: undefined, isLoading: true, isError: false });
    render(<InspectionCycleStatusTable selectedId={null} onSelectRow={vi.fn()} />);

    expect(screen.getByText('불러오는 중...')).toBeTruthy();
  });

  it('에러면 에러 문구를 렌더링한다', () => {
    mockUseInspectionCycleStatusRows.mockReturnValue({ data: undefined, isLoading: false, isError: true });
    render(<InspectionCycleStatusTable selectedId={null} onSelectRow={vi.fn()} />);

    expect(screen.getByText('점검 주기 현황을 불러오지 못했습니다.')).toBeTruthy();
  });
});
