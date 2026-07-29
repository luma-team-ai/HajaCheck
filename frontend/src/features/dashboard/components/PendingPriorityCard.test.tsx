// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useSearchParams } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('../hooks/usePendingPriority', () => ({
  usePendingPriority: () => ({
    data: [
      {
        id: 91,
        inspectionId: 42,
        grade: 'E',
        title: '긴급 균열',
        location: '지하 1층',
        occurredAt: '2026-07-28T00:00:00.000Z',
      },
    ],
    isLoading: false,
    isError: false,
  }),
}));

import { PendingPriorityCard } from './PendingPriorityCard';

afterEach(() => cleanup());

describe('PendingPriorityCard', () => {
  it('검수하기는 inspectionId의 하자 목록으로 이동하면서 defectId를 쿼리파라미터로 실어 상세 모달을 딥링크한다', async () => {
    render(
      <MemoryRouter initialEntries={['/dashboard']}>
        <Routes>
          <Route path="/dashboard" element={<PendingPriorityCard />} />
          <Route
            path="/inspections/42/defects"
            element={<DefectsPathProbe />}
          />
        </Routes>
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByRole('button', { name: '검수하기' }));

    expect(await screen.findByText('점검 42 하자 목록, defectId=91')).not.toBeNull();
  });
});

function DefectsPathProbe() {
  const [searchParams] = useSearchParams();
  return <div>점검 42 하자 목록, defectId={searchParams.get('defectId')}</div>;
}
