// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Facility } from '../types';
import { FacilityTable } from './FacilityTable';

afterEach(cleanup);

const mockFacility: Facility = {
  id: 1,
  ownerId: 1,
  name: '강남 오피스타워 A동',
  type: '건물',
  address: '서울 강남구 테헤란로 123',
  latitude: 37.5006,
  longitude: 127.0364,
  builtYear: 2008,
  scale: '지상 20층, 지하 5층',
  inspectionCycleMonths: 6,
  nextInspectionDueAt: '2026-09-15',
  createdAt: '2026-01-10T09:00:00.000Z',
  updatedAt: '2026-01-10T09:00:00.000Z',
};

// FacilityTable은 데이터를 직접 조회하지 않는 순수 프레젠테이셔널 컴포넌트이므로
// props만으로 4상태(로딩/에러/빈목록/데이터)를 검증한다(MSW 불필요).
describe('FacilityTable', () => {
  it('로딩 상태: isLoading이면 로딩 문구를 표시하고 테이블은 렌더링하지 않는다', () => {
    render(
      <FacilityTable
        facilities={undefined}
        isLoading
        isError={false}
        onRetry={vi.fn()}
        onSelectFacility={vi.fn()}
      />,
    );

    expect(screen.getByRole('status').textContent).toBe('불러오는 중...');
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('에러 상태: isError이면 에러 메시지와 다시 시도 버튼을 표시한다', () => {
    const handleRetry = vi.fn();
    render(
      <FacilityTable
        facilities={undefined}
        isLoading={false}
        isError
        onRetry={handleRetry}
        onSelectFacility={vi.fn()}
      />,
    );

    expect(screen.getByRole('alert').textContent).toContain('시설물 목록을 불러오지 못했습니다.');
    screen.getByRole('button', { name: '다시 시도' }).click();
    expect(handleRetry).toHaveBeenCalledTimes(1);
  });

  it('빈 목록 상태: 데이터가 빈 배열이면 안내 문구를 표시한다', () => {
    render(
      <FacilityTable
        facilities={[]}
        isLoading={false}
        isError={false}
        onRetry={vi.fn()}
        onSelectFacility={vi.fn()}
      />,
    );

    expect(screen.getByRole('table')).not.toBeNull();
    expect(screen.getByText('등록된 시설물이 없습니다. 시설물을 등록해 주세요.')).not.toBeNull();
  });

  it('데이터 상태: facilities가 있으면 각 행을 렌더링한다', () => {
    render(
      <FacilityTable
        facilities={[mockFacility]}
        isLoading={false}
        isError={false}
        onRetry={vi.fn()}
        onSelectFacility={vi.fn()}
      />,
    );

    expect(screen.getByText('강남 오피스타워 A동')).not.toBeNull();
    expect(screen.getByText('건물')).not.toBeNull();
    expect(screen.getByText('서울 강남구 테헤란로 123')).not.toBeNull();
    expect(screen.getByText('6개월')).not.toBeNull();
    expect(screen.getByText('2026-09-15')).not.toBeNull();
  });

  it('이름을 클릭하면 onSelectFacility가 해당 시설물 id로 호출된다', () => {
    const handleSelect = vi.fn();
    render(
      <FacilityTable
        facilities={[mockFacility]}
        isLoading={false}
        isError={false}
        onRetry={vi.fn()}
        onSelectFacility={handleSelect}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: '강남 오피스타워 A동' }));

    expect(handleSelect).toHaveBeenCalledWith(1);
  });
});