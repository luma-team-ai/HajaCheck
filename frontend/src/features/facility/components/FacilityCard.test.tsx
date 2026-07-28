// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Facility } from '../types';
import { FacilityCard } from './FacilityCard';

afterEach(cleanup);

const baseFacility: Facility = {
  id: 1,
  name: '강남 오피스타워 A동',
  type: '건물',
  address: '서울 강남구 테헤란로 123',
  latitude: 37.5006,
  longitude: 127.0364,
  builtYear: 2008,
  scale: '지상 20층, 지하 5층',
  inspectionCycleMonths: 6,
  nextInspectionDueAt: null,
  createdAt: '2026-01-10T09:00:00.000Z',
  updatedAt: '2026-01-10T09:00:00.000Z',
  initialGrade: null,
  assigneeUserId: null,
  memo: null,
  latestDefectId: null,
  thumbnailUrl: null,
  lastInspectedAt: null,
};

describe('FacilityCard', () => {
  it('이름/유형·주소·준공연도 부제/최근 점검일을 렌더링한다', () => {
    render(
      <FacilityCard
        facility={{ ...baseFacility, lastInspectedAt: '2026-06-21' }}
        onSelect={vi.fn()}
      />,
    );

    expect(screen.getByText('강남 오피스타워 A동')).not.toBeNull();
    expect(screen.getByText('건물 · 서울 강남구 테헤란로 123 · 준공 2008')).not.toBeNull();
    expect(screen.getByText('최근 점검 06.21')).not.toBeNull();
  });

  it('대표 사진이 없으면 "사진 없음" 폴백을 표시한다', () => {
    render(<FacilityCard facility={baseFacility} onSelect={vi.fn()} />);

    expect(screen.getByText('사진 없음')).not.toBeNull();
    expect(screen.queryByRole('img')).toBeNull();
  });

  it('대표 사진이 있으면 이미지를 렌더링한다', () => {
    render(
      <FacilityCard
        facility={{ ...baseFacility, thumbnailUrl: '/api/media/1/thumbnail' }}
        onSelect={vi.fn()}
      />,
    );

    const img = screen.getByRole('img', { name: '강남 오피스타워 A동' }) as HTMLImageElement;
    expect(img.src).toContain('/api/media/1/thumbnail');
    expect(screen.queryByText('사진 없음')).toBeNull();
  });

  it('등급이 있으면 등급 배지를 표시한다', () => {
    render(<FacilityCard facility={{ ...baseFacility, initialGrade: 'E' }} onSelect={vi.fn()} />);

    expect(screen.getByText('E 등급')).not.toBeNull();
  });

  it('등급이 없으면 등급 배지를 표시하지 않는다', () => {
    render(<FacilityCard facility={baseFacility} onSelect={vi.fn()} />);

    expect(screen.queryByText(/등급/)).toBeNull();
  });

  it('다음 점검일이 임박(7일 이내)이면 D-day 배지를 표시한다', () => {
    const today = new Date();
    const soon = new Date(today);
    soon.setDate(today.getDate() + 3);
    const soonIso = soon.toISOString().slice(0, 10);

    render(
      <FacilityCard facility={{ ...baseFacility, nextInspectionDueAt: soonIso }} onSelect={vi.fn()} />,
    );

    expect(screen.getByText('다음 점검일 D-3')).not.toBeNull();
  });

  it('다음 점검일이 여유 있으면 D-day 배지를 표시하지 않는다', () => {
    const today = new Date();
    const far = new Date(today);
    far.setDate(today.getDate() + 90);
    const farIso = far.toISOString().slice(0, 10);

    render(
      <FacilityCard facility={{ ...baseFacility, nextInspectionDueAt: farIso }} onSelect={vi.fn()} />,
    );

    expect(screen.queryByText(/다음 점검일/)).toBeNull();
  });

  it('점검 이력이 없으면 "점검 이력 없음"을 표시한다', () => {
    render(<FacilityCard facility={baseFacility} onSelect={vi.fn()} />);

    expect(screen.getByText('점검 이력 없음')).not.toBeNull();
  });

  it('카드를 클릭하면 onSelect가 시설물 id와 latestDefectId로 호출된다', () => {
    const handleSelect = vi.fn();
    render(
      <FacilityCard facility={{ ...baseFacility, latestDefectId: 777 }} onSelect={handleSelect} />,
    );

    fireEvent.click(screen.getByRole('button', { name: /강남 오피스타워 A동/ }));

    expect(handleSelect).toHaveBeenCalledWith(1, 777);
  });
});
