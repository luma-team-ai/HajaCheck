// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Facility } from '../types';
import { FACILITY_LIST_FILTERS_INITIAL } from '../utils/filterFacilities';
import { FacilityFilterBar } from './FacilityFilterBar';

afterEach(cleanup);

const facilities: Facility[] = [
  {
    id: 1,
    name: '강남 오피스타워 A동',
    type: '건물',
    address: '서울 강남구 테헤란로 123',
    latitude: null,
    longitude: null,
    builtYear: null,
    scale: null,
    inspectionCycleMonths: null,
    nextInspectionDueAt: null,
    createdAt: '2026-01-01T00:00:00.000Z',
    updatedAt: '2026-01-01T00:00:00.000Z',
    initialGrade: 'B',
    assigneeUserId: null,
    memo: null,
    latestDefectId: null,
    thumbnailUrl: null,
    lastInspectedAt: null,
    defectCount: 0,
  },
  {
    id: 2,
    name: '한강대교 북단',
    type: '교량',
    address: '경기 성남시 분당구 판교역로 235',
    latitude: null,
    longitude: null,
    builtYear: null,
    scale: null,
    inspectionCycleMonths: null,
    nextInspectionDueAt: null,
    createdAt: '2026-01-01T00:00:00.000Z',
    updatedAt: '2026-01-01T00:00:00.000Z',
    initialGrade: null,
    assigneeUserId: null,
    memo: null,
    latestDefectId: null,
    thumbnailUrl: null,
    lastInspectedAt: null,
    defectCount: 0,
  },
];

describe('FacilityFilterBar', () => {
  it('검색어를 입력하면 onChange가 search 필드만 갱신해 호출된다', () => {
    const handleChange = vi.fn();
    render(
      <FacilityFilterBar
        facilities={facilities}
        filters={FACILITY_LIST_FILTERS_INITIAL}
        onChange={handleChange}
      />,
    );

    fireEvent.change(screen.getByLabelText('시설물 이름 검색'), { target: { value: '강남' } });

    expect(handleChange).toHaveBeenCalledWith({ ...FACILITY_LIST_FILTERS_INITIAL, search: '강남' });
  });

  it('유형 드롭다운은 로드된 시설물에서 파생된 distinct 옵션만 노출한다', () => {
    render(
      <FacilityFilterBar
        facilities={facilities}
        filters={FACILITY_LIST_FILTERS_INITIAL}
        onChange={vi.fn()}
      />,
    );

    const typeSelect = screen.getByLabelText('유형 필터') as HTMLSelectElement;
    const optionLabels = Array.from(typeSelect.options).map((option) => option.value);
    expect(optionLabels).toEqual(['', '건물', '교량']);
  });

  it('지역 선택 시 onChange가 region 필드를 갱신해 호출된다', () => {
    const handleChange = vi.fn();
    render(
      <FacilityFilterBar
        facilities={facilities}
        filters={FACILITY_LIST_FILTERS_INITIAL}
        onChange={handleChange}
      />,
    );

    fireEvent.change(screen.getByLabelText('시설물 지역 필터'), { target: { value: '경기' } });

    expect(handleChange).toHaveBeenCalledWith({ ...FACILITY_LIST_FILTERS_INITIAL, region: '경기' });
  });

  it('등급 선택 시 onChange가 grade 필드를 갱신해 호출된다', () => {
    const handleChange = vi.fn();
    render(
      <FacilityFilterBar
        facilities={facilities}
        filters={FACILITY_LIST_FILTERS_INITIAL}
        onChange={handleChange}
      />,
    );

    fireEvent.change(screen.getByLabelText('초기 등급 필터'), { target: { value: 'A' } });

    expect(handleChange).toHaveBeenCalledWith({ ...FACILITY_LIST_FILTERS_INITIAL, grade: 'A' });
  });
});
