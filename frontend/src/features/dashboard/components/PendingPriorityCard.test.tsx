// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
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
  it('검수하기는 하자 id가 아닌 inspectionId의 하자 목록으로 이동한다', async () => {
    render(
      <MemoryRouter initialEntries={['/dashboard']}>
        <Routes>
          <Route path="/dashboard" element={<PendingPriorityCard />} />
          <Route path="/inspections/42/defects" element={<div>점검 42 하자 목록</div>} />
        </Routes>
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByRole('button', { name: '검수하기' }));

    expect(await screen.findByText('점검 42 하자 목록')).not.toBeNull();
  });
});
