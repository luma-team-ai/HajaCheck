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

  it('roving tabindex — 초기엔 첫 행만 Tab 정지점(0), 나머지는 -1', () => {
    mockData(SAMPLE);
    render(<RecentInspectionsTable />);

    expect(rowOf('A동').getAttribute('tabindex')).toBe('0');
    expect(rowOf('B동').getAttribute('tabindex')).toBe('-1');
  });

  it('ArrowDown으로 포커스가 다음 행으로 이동한다(roving)', () => {
    mockData(SAMPLE);
    render(<RecentInspectionsTable />);

    fireEvent.keyDown(rowOf('A동'), { key: 'ArrowDown' });

    expect(rowOf('A동').getAttribute('tabindex')).toBe('-1');
    expect(rowOf('B동').getAttribute('tabindex')).toBe('0');
  });

  it('ArrowUp/Home/End로 포커스 행이 이동하며 경계를 넘지 않는다', () => {
    mockData(SAMPLE);
    render(<RecentInspectionsTable />);

    // End → 마지막 행
    fireEvent.keyDown(rowOf('A동'), { key: 'End' });
    expect(rowOf('B동').getAttribute('tabindex')).toBe('0');
    // 마지막에서 ArrowDown → 그대로 유지(경계)
    fireEvent.keyDown(rowOf('B동'), { key: 'ArrowDown' });
    expect(rowOf('B동').getAttribute('tabindex')).toBe('0');
    // Home → 첫 행
    fireEvent.keyDown(rowOf('B동'), { key: 'Home' });
    expect(rowOf('A동').getAttribute('tabindex')).toBe('0');
    // 첫 행에서 ArrowUp → 그대로 유지(경계)
    fireEvent.keyDown(rowOf('A동'), { key: 'ArrowUp' });
    expect(rowOf('A동').getAttribute('tabindex')).toBe('0');
  });

  it('방향키 이동은 선택 상태를 바꾸지 않는다(포커스와 선택 분리)', () => {
    mockData(SAMPLE);
    render(<RecentInspectionsTable />);

    fireEvent.keyDown(rowOf('A동'), { key: 'ArrowDown' });

    expect(rowOf('A동').getAttribute('aria-selected')).toBe('false');
    expect(rowOf('B동').getAttribute('aria-selected')).toBe('false');
  });

  it('행 수가 줄어도 항상 한 행은 Tab 정지점(0)을 유지한다(회귀 방지)', () => {
    const THREE: RecentInspectionItem[] = [
      ...SAMPLE,
      { id: 3, facilityName: 'C동', inspectedAt: '2026-07-03', inspector: '이영희', defectCount: 1, status: '검수대기' },
    ];
    mockData(THREE);
    const { rerender } = render(<RecentInspectionsTable />);

    // 마지막 행으로 포커스 이동(focusedIndex=2)
    fireEvent.keyDown(rowOf('A동'), { key: 'End' });
    expect(rowOf('C동').getAttribute('tabindex')).toBe('0');

    // 데이터가 2행으로 줄어들면 어떤 행이든 tabIndex=0 이 정확히 하나 존재해야 함(도달 불가 방지)
    mockData(SAMPLE);
    rerender(<RecentInspectionsTable />);
    const tabbable = [rowOf('A동'), rowOf('B동')].filter((r) => r.getAttribute('tabindex') === '0');
    expect(tabbable.length).toBe(1);
  });

  it('표에 접근성 이름(aria-label)을 부여한다', () => {
    mockData(SAMPLE);
    render(<RecentInspectionsTable />);

    // 행 단위 roving + aria-selected와 일치하도록 암시적 table role 유지(grid 승격 안 함)
    expect(screen.getByRole('table', { name: '최근 점검 목록' })).toBeTruthy();
    expect(screen.queryByRole('grid')).toBeNull();
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
