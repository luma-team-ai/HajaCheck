// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import type { UpcomingInspectionItem } from '../types';
import { UpcomingInspectionCard } from './UpcomingInspectionCard';

afterEach(() => cleanup());

const baseItem: UpcomingInspectionItem = {
  facilityId: 1,
  facilityName: '한강대교 북단',
  nextInspectionDueAt: '2026-07-29',
  dDay: 7,
  inspectionCycleMonths: 12,
};

function renderCard(item: UpcomingInspectionItem = baseItem) {
  render(
    <MemoryRouter initialEntries={['/dashboard/upcoming-inspections']}>
      <Routes>
        <Route path="/dashboard/upcoming-inspections" element={<UpcomingInspectionCard item={item} />} />
        <Route path="/inspections/create" element={<div>점검 생성 화면</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('UpcomingInspectionCard', () => {
  it('시설물명·점검주기·D-DAY를 표시한다', () => {
    renderCard();
    expect(screen.getByText('한강대교 북단')).not.toBeNull();
    expect(screen.getByText('12개월 주기')).not.toBeNull();
    expect(screen.getByLabelText('다음 점검일 D-7')).not.toBeNull();
  });

  it('inspectionCycleMonths가 null이면 주기 텍스트를 표시하지 않는다', () => {
    renderCard({ ...baseItem, inspectionCycleMonths: null });
    expect(screen.queryByText(/개월 주기/)).toBeNull();
  });

  it('"점검 생성" 클릭 시 facilityId 쿼리와 함께 점검 생성 화면으로 이동한다', async () => {
    renderCard();

    fireEvent.click(screen.getByRole('button', { name: '점검 생성' }));

    expect(await screen.findByText('점검 생성 화면')).not.toBeNull();
  });
});
