// @vitest-environment jsdom
import { cleanup, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { InspectionTable } from './InspectionTable';
import type { InspectionListItem } from '../types';

// 컬럼 순서(InspectionTable.createColumns): 선택/ID, 시설물, 점검일, 회차, 하자건수, 등급분포, 상태, 담당자
const GRADE_DISTRIBUTION_CELL_INDEX = 5;
const ASSIGNEE_CELL_INDEX = 7;

function cellsOf(facilityName: string): HTMLElement[] {
  const row = screen.getByText(facilityName).closest('tr');
  if (!row) throw new Error(`row not found for ${facilityName}`);
  return within(row).getAllByRole('cell');
}

afterEach(cleanup);

const BASE_INSPECTION: InspectionListItem = {
  id: 101,
  facilityId: 1,
  facilityName: '강남 오피스타워 A동',
  facilityType: '건물',
  roundNo: 3,
  inspectionDate: '2026-07-01',
  status: 'REVIEWED',
  defectCount: 3,
  gradeDistribution: { A: 0, B: 2, C: 1, D: 0, E: 0 },
  assigneeName: '김도현 검사자',
};

function renderTable(inspections: InspectionListItem[] | undefined) {
  return render(
    <MemoryRouter>
      <InspectionTable
        inspections={inspections}
        isLoading={false}
        isError={false}
        onRetry={vi.fn()}
        selectedIds={new Set()}
        onSelectionChange={vi.fn()}
      />
    </MemoryRouter>,
  );
}

describe('InspectionTable', () => {
  it('gradeDistribution이 채워진 row는 0건이 아닌 등급만 배지로 렌더한다', () => {
    renderTable([BASE_INSPECTION]);

    expect(screen.getByTitle('B등급 2건')).toBeTruthy();
    expect(screen.getByTitle('C등급 1건')).toBeTruthy();
    // A/D/E는 0건이라 배지로 렌더되지 않는다
    expect(screen.queryByTitle('A등급 0건')).toBeNull();
    expect(screen.queryByTitle('D등급 0건')).toBeNull();
    expect(screen.queryByTitle('E등급 0건')).toBeNull();
  });

  // #893 회귀 방지 — 백엔드/MSW 계약 불일치로 gradeDistribution이 undefined인 row가 섞여 있어도
  // "Cannot read properties of undefined (reading 'A')"로 크래시하지 않고 정상 렌더돼야 한다.
  it('gradeDistribution이 undefined인 row가 섞여 있어도 크래시 없이 렌더된다', () => {
    const brokenInspection = {
      ...BASE_INSPECTION,
      id: 202,
      facilityName: '한강대교 북단',
      gradeDistribution: undefined,
    } as unknown as InspectionListItem;

    expect(() => renderTable([BASE_INSPECTION, brokenInspection])).not.toThrow();

    expect(screen.getByText('강남 오피스타워 A동')).toBeTruthy();
    expect(screen.getByText('한강대교 북단')).toBeTruthy();
    // undefined row는 EMPTY_GRADE_DISTRIBUTION으로 폴백돼 모든 등급이 0건 → 등급분포 칸이 "-"
    expect(cellsOf('한강대교 북단')[GRADE_DISTRIBUTION_CELL_INDEX].textContent).toBe('-');
  });

  it('assigneeName이 null이면 "-"가 렌더된다', () => {
    renderTable([{ ...BASE_INSPECTION, assigneeName: null }]);

    expect(cellsOf('강남 오피스타워 A동')[ASSIGNEE_CELL_INDEX].textContent).toBe('-');
  });

  it('로딩 중이면 로딩 문구를 렌더링한다', () => {
    render(
      <MemoryRouter>
        <InspectionTable
          inspections={undefined}
          isLoading
          isError={false}
          onRetry={vi.fn()}
          selectedIds={new Set()}
          onSelectionChange={vi.fn()}
        />
      </MemoryRouter>,
    );

    expect(screen.getByText('점검 목록을 불러오는 중입니다')).toBeTruthy();
  });

  it('에러면 에러 문구를 렌더링한다', () => {
    render(
      <MemoryRouter>
        <InspectionTable
          inspections={undefined}
          isLoading={false}
          isError
          onRetry={vi.fn()}
          selectedIds={new Set()}
          onSelectionChange={vi.fn()}
        />
      </MemoryRouter>,
    );

    expect(screen.getByText('점검 목록을 불러오지 못했습니다.')).toBeTruthy();
  });

  it('데이터가 비어 있으면 안내 문구를 렌더링한다', () => {
    renderTable([]);

    expect(screen.getByText('조회된 점검이 없습니다. 필터 조건을 변경해 보세요.')).toBeTruthy();
  });
});
