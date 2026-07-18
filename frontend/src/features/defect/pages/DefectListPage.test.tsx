// @vitest-environment jsdom
// DefectListPage 통합 테스트 — 실제 useDefects 훅 + MSW defectHandlers를 통해 목록 렌더링과
// 필터 적용을 검증한다(HAJA-30, FacilityListPage.test.tsx와 동일 패턴).
// 필터 드롭다운 옵션 라벨(예: "균열", "철근 노출")이 테이블 셀 텍스트와 동일해 화면 전체 대상
// screen.getByText는 모호하게 매치될 수 있다 — 모든 데이터 검증은 within(table)로 범위를 좁힌다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { MemoryRouter } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { defectHandlers } from '../api/defectApi.handlers';
import { DefectListPage } from './DefectListPage';

const server = setupServer(...defectHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

function renderPage(): void {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <DefectListPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('DefectListPage (통합 테스트)', () => {
  it('초기 목록: MSW 목 데이터를 불러와 테이블에 렌더링한다', async () => {
    renderPage();

    const table = await screen.findByRole('table');
    expect(within(table).getByText('철근 노출')).not.toBeNull();
    expect(within(table).getByText('균열')).not.toBeNull();
    expect(within(table).getByText('박리·박락')).not.toBeNull();
  });

  it('등급 필터 적용: 선택한 등급의 하자만 남는다', async () => {
    renderPage();
    await screen.findByRole('table');

    fireEvent.change(screen.getByLabelText('등급 필터'), { target: { value: 'D' } });

    await waitFor(() => {
      const table = screen.getByRole('table');
      expect(within(table).getByText('철근 노출')).not.toBeNull();
      expect(within(table).queryByText('균열')).toBeNull();
    });
  });

  it('필터 초기화: 다시 전체 목록이 보인다', async () => {
    renderPage();
    await screen.findByRole('table');

    fireEvent.change(screen.getByLabelText('유형 필터'), { target: { value: 'CRACK' } });
    await waitFor(() => {
      const table = screen.getByRole('table');
      expect(within(table).getByText('균열')).not.toBeNull();
      expect(within(table).queryByText('철근 노출')).toBeNull();
    });

    fireEvent.click(screen.getByRole('button', { name: '필터 초기화' }));

    await waitFor(() => {
      const table = screen.getByRole('table');
      expect(within(table).getByText('철근 노출')).not.toBeNull();
      expect(within(table).getByText('균열')).not.toBeNull();
    });
  });

  it('상세보기 링크가 각 행에 렌더링된다', async () => {
    renderPage();
    const table = await screen.findByRole('table');

    const detailLinks = within(table).getAllByRole('link', { name: '상세보기' });
    expect(detailLinks.length).toBeGreaterThan(0);
    expect(detailLinks[0].getAttribute('href')).toMatch(/^\/defects\/\d+$/);
  });
});
