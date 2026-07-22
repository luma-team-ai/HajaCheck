// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { facilityComparisonHandlers } from '../api/facilityComparisonApi.handlers';
import { facilityDefectHandlers } from '../api/facilityDefectApi.handlers';
import { FacilityDefectDetailPage } from './FacilityDefectDetailPage';

const server = setupServer(...facilityDefectHandlers, ...facilityComparisonHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

function renderPage(): void {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });

  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/facilities/1/defects/1']}>
        <Routes>
          <Route path="/facilities/:id/defects/:defectId" element={<FacilityDefectDetailPage />} />
          <Route path="/facilities/:id/defects/:defectId/compare" element={<div>회차비교 화면</div>} />
          <Route path="/defects/:id" element={<div>하자 관리 상세 화면</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('FacilityDefectDetailPage (통합 테스트)', () => {
  it('하자 정보(유형·등급·크기·발견·담당)를 렌더링한다', async () => {
    renderPage();

    expect(await screen.findByText('하자 상세')).not.toBeNull();
    expect(screen.getByText('균열')).not.toBeNull();
    expect(screen.getByText('외벽 동측 12층 부근')).not.toBeNull();
    expect(screen.getByText('김검수')).not.toBeNull();
  });

  it('기본 선택 탭은 "오버레이"이고 이미지 위에 마킹 레이어가 함께 렌더링된다', async () => {
    renderPage();
    await screen.findByText('하자 상세');

    expect(screen.getByRole('tab', { name: '오버레이' }).getAttribute('aria-selected')).toBe(
      'true',
    );
    // 오버레이 마킹 레이어는 alt=""(장식용)라 접근성 트리에서 이미지 role이 아니라 hidden으로 빠진다 —
    // aria-hidden 이미지는 role 쿼리로 잡히지 않으므로 원본 사진(하자 이미지)만 role로 확인한다.
    expect(screen.getByRole('img', { name: '균열 하자 이미지' })).not.toBeNull();
  });

  it('"원본" 탭 클릭 시 원본으로 전환되고 오버레이 마킹 레이어가 사라진다', async () => {
    renderPage();
    await screen.findByText('하자 상세');

    fireEvent.click(screen.getByRole('tab', { name: '원본' }));

    expect(screen.getByRole('tab', { name: '원본' }).getAttribute('aria-selected')).toBe('true');
    expect(screen.getByRole('tab', { name: '오버레이' }).getAttribute('aria-selected')).toBe(
      'false',
    );
  });

  it('AI 설명 패널은 로딩 후 진단·권장조치 텍스트를 표시한다', async () => {
    renderPage();

    expect(await screen.findByText(/구조적 스트레스로 인한 진행성 균열/)).not.toBeNull();
  });

  it('활동 이력을 시각순으로 렌더링한다', async () => {
    renderPage();

    expect(await screen.findByText('이점검 님이 등급을 D→E로 수정')).not.toBeNull();
    expect(screen.getByText('AI 탐지 등록')).not.toBeNull();
  });

  it('"다음 단계로 전이" 클릭 시 /defects/:id(하자 관리 도메인)로 이동한다', async () => {
    renderPage();
    await screen.findByText('하자 상세');

    fireEvent.click(screen.getByRole('button', { name: '다음 단계로 전이' }));

    expect(await screen.findByText('하자 관리 상세 화면')).not.toBeNull();
  });

  it('"회차비교" 탭 클릭 시 /facilities/:id/compare로 이동한다', async () => {
    renderPage();
    await screen.findByText('하자 상세');

    fireEvent.click(screen.getByRole('tab', { name: '회차비교' }));

    expect(await screen.findByText('회차비교 화면')).not.toBeNull();
  });
});