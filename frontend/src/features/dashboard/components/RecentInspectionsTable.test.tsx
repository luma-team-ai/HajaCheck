// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { RecentInspectionsTable } from './RecentInspectionsTable';
import { DASHBOARD_COLOR_CLASS } from '../colors';
import type { RecentInspectionItem } from '../types';

const mockUseRecentInspections = vi.fn();
vi.mock('../hooks/useRecentInspections', () => ({
  useRecentInspections: () => mockUseRecentInspections(),
}));

const SAMPLE: RecentInspectionItem[] = [
  { id: 1, facilityName: 'A동', inspectedAt: '2026-07-01', inspector: '홍길동', defectCount: 2, status: '완료' },
  { id: 2, facilityName: 'B동', inspectedAt: '2026-07-02', inspector: '김철수', defectCount: 0, status: '분석중' },
];

function mockData(data: RecentInspectionItem[]) {
  mockUseRecentInspections.mockReturnValue({ data, isLoading: false, isError: false });
}

// 행 = 텍스트로 찾은 셀의 상위 tr (thead 행과 구분)
function rowOf(facilityName: string): HTMLTableRowElement {
  const cell = screen.getByText(facilityName);
  const row = cell.closest('tr');
  if (!row) throw new Error(`row not found for ${facilityName}`);
  return row as HTMLTableRowElement;
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('RecentInspectionsTable', () => {
  it('데이터가 있으면 각 점검 행을 렌더링한다', () => {
    mockData(SAMPLE);
    render(<RecentInspectionsTable />);

    expect(screen.getByText('A동')).toBeTruthy();
    expect(screen.getByText('B동')).toBeTruthy();
  });

  it('초기 상태에서는 선택된 행이 없다(aria-selected=false)', () => {
    mockData(SAMPLE);
    render(<RecentInspectionsTable />);

    expect(rowOf('A동').getAttribute('aria-selected')).toBe('false');
    expect(rowOf('B동').getAttribute('aria-selected')).toBe('false');
  });

  it('행을 클릭하면 선택되고 선택 배경 클래스가 적용된다', () => {
    mockData(SAMPLE);
    render(<RecentInspectionsTable />);

    fireEvent.click(rowOf('A동'));

    const row = rowOf('A동');
    expect(row.getAttribute('aria-selected')).toBe('true');
    expect(row.className).toContain(DASHBOARD_COLOR_CLASS.rowSelectedBg);
    // 첫 칸에 좌측 강조 바
    const firstCell = screen.getByText('A동');
    expect(firstCell.className).toContain(DASHBOARD_COLOR_CLASS.rowSelectedBar);
  });

  it('선택된 행을 다시 클릭하면 선택이 해제된다(토글)', () => {
    mockData(SAMPLE);
    render(<RecentInspectionsTable />);

    fireEvent.click(rowOf('A동'));
    expect(rowOf('A동').getAttribute('aria-selected')).toBe('true');

    fireEvent.click(rowOf('A동'));
    expect(rowOf('A동').getAttribute('aria-selected')).toBe('false');
  });

  it('다른 행을 클릭하면 선택이 그 행으로 이동한다', () => {
    mockData(SAMPLE);
    render(<RecentInspectionsTable />);

    fireEvent.click(rowOf('A동'));
    fireEvent.click(rowOf('B동'));

    expect(rowOf('A동').getAttribute('aria-selected')).toBe('false');
    expect(rowOf('B동').getAttribute('aria-selected')).toBe('true');
  });

  it('Enter 키로 행을 선택할 수 있다', () => {
    mockData(SAMPLE);
    render(<RecentInspectionsTable />);

    fireEvent.keyDown(rowOf('A동'), { key: 'Enter' });

    expect(rowOf('A동').getAttribute('aria-selected')).toBe('true');
  });

  it('Space 키로 행을 선택할 수 있다', () => {
    mockData(SAMPLE);
    render(<RecentInspectionsTable />);

    fireEvent.keyDown(rowOf('B동'), { key: ' ' });

    expect(rowOf('B동').getAttribute('aria-selected')).toBe('true');
  });

  it('각 행은 키보드 포커스가 가능하다(tabIndex=0)', () => {
    mockData(SAMPLE);
    render(<RecentInspectionsTable />);

    expect(rowOf('A동').getAttribute('tabindex')).toBe('0');
  });

  it('데이터가 비어 있으면 안내 문구를 렌더링한다', () => {
    mockData([]);
    render(<RecentInspectionsTable />);

    expect(screen.getByText('최근 점검 이력이 없습니다.')).toBeTruthy();
  });

  it('로딩 중이면 로딩 문구를 렌더링한다', () => {
    mockUseRecentInspections.mockReturnValue({ data: undefined, isLoading: true, isError: false });
    render(<RecentInspectionsTable />);

    expect(screen.getByText('불러오는 중...')).toBeTruthy();
  });

  it('에러면 에러 문구를 렌더링한다', () => {
    mockUseRecentInspections.mockReturnValue({ data: undefined, isLoading: false, isError: true });
    render(<RecentInspectionsTable />);

    expect(screen.getByText('최근 점검 목록을 불러오지 못했습니다.')).toBeTruthy();
  });
});
