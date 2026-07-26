// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { HttpResponse, http } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { dashboardHandlers } from '../api/dashboardApi.handlers';
import { mockRecentInspectionsFull } from '../mocks/dashboard.mock';
import { RecentInspectionsFullListPage } from './RecentInspectionsFullListPage';

const server = setupServer(...dashboardHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/dashboard/recent-inspections']}>
        <Routes>
          <Route path="/dashboard/recent-inspections" element={<RecentInspectionsFullListPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('RecentInspectionsFullListPage', () => {
  it('목록과 총 건수를 렌더링한다(기본 페이지 크기=10)', async () => {
    renderPage();

    // 시설물 select 옵션과 표 셀에 동일 텍스트가 동시에 존재할 수 있어(facilityId 필터 옵션이
    // 목 데이터의 시설물명과 겹침) role='cell'로 표 본문만 특정한다.
    expect(await screen.findByRole('cell', { name: '여의도 파크센터' })).not.toBeNull();
    // mockRecentInspectionsFull은 22건 — 기본 size=10이라 화면엔 상위 10건만, 총 건수는 전체를 표시.
    expect(screen.getByText(`총 ${mockRecentInspectionsFull.length}건`)).not.toBeNull();
    expect(screen.getAllByRole('row')).toHaveLength(11); // header 1 + body 10
  });

  it('검색어 입력 시 디바운스 후 필터링된 결과만 남는다', async () => {
    renderPage();
    await screen.findByRole('cell', { name: '여의도 파크센터' });

    fireEvent.change(screen.getByLabelText('시설물, 담당자 검색'), { target: { value: '강남' } });

    await waitFor(() => {
      expect(screen.getByRole('cell', { name: '강남 오피스타워' })).not.toBeNull();
      expect(screen.queryByRole('cell', { name: '여의도 파크센터' })).toBeNull();
    });
  });

  it('상태 필터 pill 클릭 시 해당 상태만 조회한다', async () => {
    renderPage();
    await screen.findByRole('cell', { name: '여의도 파크센터' });

    fireEvent.click(screen.getByRole('tab', { name: '완료' }));

    // findBy*로 새 조회가 끝날 때까지(로딩 스피너 구간 포함) 기다린 뒤 단언한다 — waitFor로 "사라짐"만
    // 먼저 확인하면 로딩 중(테이블 자체가 아직 안 그려진 순간)에도 통과해버려 다음 동기 단언이
    // 로딩 스피너 상태와 경합하는 flaky 패턴이 된다.
    expect(await screen.findByRole('cell', { name: '송도 물류센터' })).not.toBeNull(); // 완료 상태
    expect(screen.queryByRole('cell', { name: '여의도 파크센터' })).toBeNull(); // 검수대기 상태라 제외됨
  });

  it('페이지네이션 — 다음 페이지 클릭 시 다음 항목이 표시된다', async () => {
    renderPage();
    await screen.findByRole('cell', { name: '여의도 파크센터' });
    // mockRecentInspectionsFull의 11번째(index 10) 항목 — 기본 size=10이라 1페이지(0~9)엔 없다.
    expect(screen.queryByRole('cell', { name: '인천 국제터미널' })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '다음 페이지' }));

    await waitFor(() => {
      expect(screen.getByRole('cell', { name: '인천 국제터미널' })).not.toBeNull();
    });
  });

  it('결과가 없으면 빈 상태 문구를 표시한다', async () => {
    renderPage();
    await screen.findByRole('cell', { name: '여의도 파크센터' });

    fireEvent.change(screen.getByLabelText('시설물, 담당자 검색'), {
      target: { value: '존재하지않는검색어' },
    });

    expect(await screen.findByText('조건에 맞는 점검 이력이 없습니다.')).not.toBeNull();
  });

  it('에러 시 에러 문구와 다시 시도 버튼을 표시한다', async () => {
    server.use(
      http.get('/api/dashboard/recent-inspections/search', () => new HttpResponse(null, { status: 500 })),
    );
    renderPage();

    expect(await screen.findByText('최근 점검 목록을 불러오지 못했습니다.')).not.toBeNull();
    expect(screen.getByRole('button', { name: '다시 시도' })).not.toBeNull();
  });
});
