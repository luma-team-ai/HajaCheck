// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { InspectionCycleStatusTable } from './InspectionCycleStatusTable';
import type { InspectionCycleStatusRow } from '../types';

const mockUseInspectionCycleStatusRows = vi.fn();
vi.mock('../hooks/useInspectionCycleStatusRows', () => ({
  useInspectionCycleStatusRows: () => mockUseInspectionCycleStatusRows(),
}));

// 기준일을 2026-07-20으로 고정 — 아래 행들의 D-day가 일정하게 파생되도록.
const TODAY = new Date(2026, 6, 20);

// 필터 조합 검증용 — 기간(cycleMonths)·긴급도(overdue/upcoming/safe) 조합을 각기 다르게 둬서
// 기간·긴급도 각각의 단일 필터, 둘의 AND 결합, "3/6/12에 없는 주기는 전체에서만" 규칙까지 커버한다.
const FILTER_SAMPLE: InspectionCycleStatusRow[] = [
  {
    id: 1,
    name: '지하 발전기실',
    type: '정기',
    cycleMonths: 3,
    lastInspectedAt: '2026-06-10',
    nextInspectionDueAt: '2026-07-17', // D+3 → 초과
    assigneeName: '김관리',
  },
  {
    id: 2,
    name: '1F 로비',
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
  {
    id: 4,
    name: '옥상 공조탑',
    type: '정기',
    cycleMonths: 12,
    lastInspectedAt: '2026-01-15',
    nextInspectionDueAt: '2026-07-15', // D+5 → 초과
    assigneeName: '최엔지니어',
  },
  {
    id: 5,
    name: '지하 주차장',
    type: '정밀',
    cycleMonths: 24, // 3/6/12 버튼에 없는 주기 — '전체'에서만 노출돼야 함
    lastInspectedAt: '2026-02-10',
    nextInspectionDueAt: '2026-07-16', // D+4 → 초과
    assigneeName: '김관리',
  },
];

// 페이지네이션 검증용 — 기본(기간=전체·긴급도=미선택) 상태에서 12건이라 페이지가 2개로 나뉜다.
// 10건은 cycleMonths=6, 나머지 2건은 cycleMonths=3 — 기간 필터 전환 시 1페이지 리셋 검증에 사용.
const PAGINATION_SAMPLE: InspectionCycleStatusRow[] = Array.from({ length: 12 }, (_, index) => {
  const id = index + 1;
  return {
    id,
    name: `시설물${String(id).padStart(2, '0')}`,
    type: '정기' as const,
    cycleMonths: id <= 10 ? 6 : 3,
    lastInspectedAt: '2026-06-01',
    nextInspectionDueAt: '2026-12-31', // 항상 여유 → 긴급도 필터와 무관하게 노출
    assigneeName: '김관리',
  };
});

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
    mockRows(FILTER_SAMPLE);
    render(<InspectionCycleStatusTable selectedId={null} onSelectRow={vi.fn()} />);

    const table = screen.getByRole('table', { name: '전체 시설물 점검 주기 현황' });
    const headers = within(table).getAllByRole('columnheader');
    expect(headers).toHaveLength(7);

    FILTER_SAMPLE.forEach((row) => expect(screen.getByText(row.name)).toBeTruthy());
  });

  describe('기간 필터(그룹1)', () => {
    it('"3개월"을 누르면 cycleMonths=3인 행만 남는다', () => {
      mockRows(FILTER_SAMPLE);
      render(<InspectionCycleStatusTable selectedId={null} onSelectRow={vi.fn()} />);

      fireEvent.click(screen.getByRole('button', { name: '3개월' }));

      expect(screen.getByText('지하 발전기실')).toBeTruthy();
      expect(screen.queryByText('1F 로비')).toBeNull();
      expect(screen.queryByText('오피스타워')).toBeNull();
      expect(screen.queryByText('옥상 공조탑')).toBeNull();
      expect(screen.queryByText('지하 주차장')).toBeNull();
    });

    it('"6개월"을 누르면 cycleMonths=6인 행만 남는다', () => {
      mockRows(FILTER_SAMPLE);
      render(<InspectionCycleStatusTable selectedId={null} onSelectRow={vi.fn()} />);

      fireEvent.click(screen.getByRole('button', { name: '6개월' }));

      expect(screen.getByText('1F 로비')).toBeTruthy();
      expect(screen.getByText('오피스타워')).toBeTruthy();
      expect(screen.queryByText('지하 발전기실')).toBeNull();
      expect(screen.queryByText('옥상 공조탑')).toBeNull();
    });

    it('"1년"을 누르면 cycleMonths=12인 행만 남고, 24개월 행은 제외된다', () => {
      mockRows(FILTER_SAMPLE);
      render(<InspectionCycleStatusTable selectedId={null} onSelectRow={vi.fn()} />);

      fireEvent.click(screen.getByRole('button', { name: '1년' }));

      expect(screen.getByText('옥상 공조탑')).toBeTruthy();
      expect(screen.queryByText('지하 주차장')).toBeNull(); // 24개월 — 1년 버튼에 안 걸림
    });

    it('24개월처럼 3버튼에 없는 주기는 "전체"에서만 노출된다', () => {
      mockRows(FILTER_SAMPLE);
      render(<InspectionCycleStatusTable selectedId={null} onSelectRow={vi.fn()} />);

      // 기본값이 이미 "전체"
      expect(screen.getByText('지하 주차장')).toBeTruthy();
    });
  });

  describe('긴급도 필터(그룹2) — 단일 선택 토글', () => {
    it('"임박"을 누르면 임박 상태 행만 남는다', () => {
      mockRows(FILTER_SAMPLE);
      render(<InspectionCycleStatusTable selectedId={null} onSelectRow={vi.fn()} />);

      fireEvent.click(screen.getByRole('button', { name: '임박' }));

      expect(screen.getByText('1F 로비')).toBeTruthy();
      expect(screen.queryByText('지하 발전기실')).toBeNull();
      expect(screen.queryByText('오피스타워')).toBeNull();
      expect(screen.queryByText('옥상 공조탑')).toBeNull();
      expect(screen.queryByText('지하 주차장')).toBeNull();
    });

    it('"초과"를 누르면 초과 상태 행만 남는다', () => {
      mockRows(FILTER_SAMPLE);
      render(<InspectionCycleStatusTable selectedId={null} onSelectRow={vi.fn()} />);

      fireEvent.click(screen.getByRole('button', { name: '초과' }));

      expect(screen.getByText('지하 발전기실')).toBeTruthy();
      expect(screen.getByText('옥상 공조탑')).toBeTruthy();
      expect(screen.getByText('지하 주차장')).toBeTruthy();
      expect(screen.queryByText('1F 로비')).toBeNull();
      expect(screen.queryByText('오피스타워')).toBeNull();
    });

    it('선택된 긴급도 버튼을 다시 누르면 해제되어 긴급도 전체로 돌아간다', () => {
      mockRows(FILTER_SAMPLE);
      render(<InspectionCycleStatusTable selectedId={null} onSelectRow={vi.fn()} />);

      const overdueButton = screen.getByRole('button', { name: '초과' });
      fireEvent.click(overdueButton);
      expect(overdueButton.getAttribute('aria-pressed')).toBe('true');
      expect(screen.queryByText('1F 로비')).toBeNull();

      fireEvent.click(overdueButton);
      expect(overdueButton.getAttribute('aria-pressed')).toBe('false');
      FILTER_SAMPLE.forEach((row) => expect(screen.getByText(row.name)).toBeTruthy());
    });
  });

  describe('기간·긴급도 AND 결합', () => {
    it('기간 "1년" + 긴급도 "초과"를 동시에 선택하면 둘 다 만족하는 행만 남는다', () => {
      mockRows(FILTER_SAMPLE);
      render(<InspectionCycleStatusTable selectedId={null} onSelectRow={vi.fn()} />);

      fireEvent.click(screen.getByRole('button', { name: '1년' }));
      fireEvent.click(screen.getByRole('button', { name: '초과' }));

      // cycleMonths=12 AND 초과 → 옥상 공조탑만. 지하 주차장은 24개월이라 제외, 지하 발전기실은 3개월이라 제외.
      expect(screen.getByText('옥상 공조탑')).toBeTruthy();
      expect(screen.queryByText('지하 발전기실')).toBeNull();
      expect(screen.queryByText('지하 주차장')).toBeNull();
    });

    it('AND 조건을 만족하는 행이 없으면 안내 문구를 렌더링한다', () => {
      mockRows(FILTER_SAMPLE);
      render(<InspectionCycleStatusTable selectedId={null} onSelectRow={vi.fn()} />);

      // 6개월 그룹에는 임박/여유만 있고 초과 행이 없다.
      fireEvent.click(screen.getByRole('button', { name: '6개월' }));
      fireEvent.click(screen.getByRole('button', { name: '초과' }));

      expect(screen.getByText('조건에 맞는 시설물이 없습니다.')).toBeTruthy();
    });
  });

  describe('페이지네이션', () => {
    it('페이지당 10건씩 나뉘어 렌더링되고 "다음 페이지"로 이동할 수 있다', () => {
      mockRows(PAGINATION_SAMPLE);
      render(<InspectionCycleStatusTable selectedId={null} onSelectRow={vi.fn()} />);

      expect(screen.getByText('시설물01')).toBeTruthy();
      expect(screen.getByText('시설물10')).toBeTruthy();
      expect(screen.queryByText('시설물11')).toBeNull();
      expect(screen.getByText('1-10 / 12')).toBeTruthy();

      fireEvent.click(screen.getByRole('button', { name: '다음 페이지' }));

      expect(screen.getByText('시설물11')).toBeTruthy();
      expect(screen.getByText('시설물12')).toBeTruthy();
      expect(screen.queryByText('시설물01')).toBeNull();
      expect(screen.getByText('11-12 / 12')).toBeTruthy();
    });

    it('필터를 변경하면 1페이지로 리셋된다', () => {
      mockRows(PAGINATION_SAMPLE);
      render(<InspectionCycleStatusTable selectedId={null} onSelectRow={vi.fn()} />);

      fireEvent.click(screen.getByRole('button', { name: '다음 페이지' }));
      expect(screen.getByRole('button', { name: '이전 페이지' }).hasAttribute('disabled')).toBe(false);
      expect(screen.getByText('시설물11')).toBeTruthy();

      // 6개월 필터 → 10건(시설물01~10)만 남아 1페이지에 전부 들어간다.
      fireEvent.click(screen.getByRole('button', { name: '6개월' }));

      expect(screen.getByText('시설물01')).toBeTruthy();
      expect(screen.queryByText('시설물11')).toBeNull();
      expect(screen.getByRole('button', { name: '이전 페이지' }).hasAttribute('disabled')).toBe(true);
      expect(screen.getByRole('button', { name: '다음 페이지' }).hasAttribute('disabled')).toBe(true);
    });
  });

  it('행을 클릭하면 onSelectRow가 해당 행 데이터로 호출된다', () => {
    mockRows(FILTER_SAMPLE);
    const onSelectRow = vi.fn();
    render(<InspectionCycleStatusTable selectedId={null} onSelectRow={onSelectRow} />);

    fireEvent.click(screen.getByText('오피스타워'));

    expect(onSelectRow).toHaveBeenCalledWith(FILTER_SAMPLE[2]);
  });

  it('선택된 행은 aria-selected=true이다', () => {
    mockRows(FILTER_SAMPLE);
    render(<InspectionCycleStatusTable selectedId={3} onSelectRow={vi.fn()} />);

    const row = screen.getByText('오피스타워').closest('tr');
    expect(row?.getAttribute('aria-selected')).toBe('true');
  });

  it('데이터가 비어 있으면 안내 문구를 렌더링한다', () => {
    mockRows([]);
    render(<InspectionCycleStatusTable selectedId={null} onSelectRow={vi.fn()} />);

    expect(screen.getByText('조건에 맞는 시설물이 없습니다.')).toBeTruthy();
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
