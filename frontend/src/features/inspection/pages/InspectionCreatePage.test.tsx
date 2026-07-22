// @vitest-environment jsdom
// InspectionCreatePage 통합 테스트 — 시설물 개요 패널(실 API + 목 통계) 렌더링과, "+ 새 점검"
// 모달을 통한 실제 점검 생성(POST /api/inspections, MSW inspectionHandlers) 플로우를 검증한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { inspectionHandlers } from '../api/inspectionApi.handlers';
import { InspectionCreatePage } from './InspectionCreatePage';

const server = setupServer(...inspectionHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

function LocationProbe() {
  const location = useLocation();
  return (
    <div data-testid="location-probe">
      {location.pathname}
      {location.search}
    </div>
  );
}

function renderPage(initialEntry = '/inspections/create') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });

  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <Routes>
          <Route path="/inspections/create" element={<InspectionCreatePage />} />
          <Route path="/facilities/:id" element={<div>시설물 상세</div>} />
        </Routes>
        <LocationProbe />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function openModal() {
  fireEvent.click(screen.getByRole('button', { name: '+ 새 점검' }));
  return screen.getByRole('dialog');
}

async function fillValidForm(dialog: HTMLElement) {
  // 시설물 옵션 로딩 중엔 select가 disabled라 fireEvent.change가 무시된다 — 옵션이 뜬 뒤에 값을 바꾼다.
  await within(dialog).findByText('판교 테크노밸리 B동');
  fireEvent.change(within(dialog).getByLabelText(/점검일/), { target: { value: '2026-08-01' } });
  fireEvent.change(within(dialog).getByLabelText(/담당자 ID/), { target: { value: '5' } });
}

describe('InspectionCreatePage (통합 테스트)', () => {
  it('시설물 개요(이름/통계/점검 이력)를 실 API + 목 데이터로 렌더링한다', async () => {
    renderPage();

    expect(await screen.findByRole('heading', { name: '강남 오피스타워 A동' })).not.toBeNull();
    expect(screen.getByText('8')).not.toBeNull();
    expect(screen.getByText('43')).not.toBeNull();
    expect(screen.getByText('12')).not.toBeNull();
    expect(screen.getByText('8회차 점검')).not.toBeNull();
  });

  it('+ 새 점검을 누르면 현재 보고 있던 시설물이 기본 선택된 생성 모달이 열린다', async () => {
    renderPage();
    await screen.findByRole('heading', { name: '강남 오피스타워 A동' });

    const dialog = openModal();

    const option = within(dialog).getByRole('option', {
      name: '강남 오피스타워 A동',
    }) as HTMLOptionElement;
    expect(option.selected).toBe(true);
  });

  it('필수값 미입력 시 제출하면 모달 안에 에러 메시지를 보여주고 요청을 보내지 않는다', async () => {
    renderPage();
    await screen.findByRole('heading', { name: '강남 오피스타워 A동' });
    const dialog = openModal();

    fireEvent.click(within(dialog).getByRole('button', { name: '점검 회차 생성' }));

    expect(await within(dialog).findByText('점검일을 선택해 주세요.')).not.toBeNull();
    expect(within(dialog).getByText('담당자 ID를 입력해 주세요.')).not.toBeNull();
  });

  it('생성 성공 시 모달이 닫히고 해당 시설물 상세 페이지로 이동한다', async () => {
    renderPage();
    await screen.findByRole('heading', { name: '강남 오피스타워 A동' });
    const dialog = openModal();
    await fillValidForm(dialog);

    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: '점검 회차 생성' }));
    });

    expect(await screen.findByText('시설물 상세')).not.toBeNull();
    expect(screen.getByTestId('location-probe').textContent).toBe('/facilities/1?facilityId=1');
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('생성 실패 시 모달이 열린 채 에러 메시지를 표시한다', async () => {
    server.use(
      http.post('/api/inspections', () => {
        const failure = {
          success: false,
          data: null,
          error: { code: 'AUTH_INVALID_INSPECTOR', message: '배정할 수 없는 담당자입니다.' },
        };
        return HttpResponse.json(failure, { status: 400 });
      }),
    );

    renderPage();
    await screen.findByRole('heading', { name: '강남 오피스타워 A동' });
    const dialog = openModal();
    await fillValidForm(dialog);

    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: '점검 회차 생성' }));
    });

    expect(await within(dialog).findByText('배정할 수 없는 담당자입니다.')).not.toBeNull();
    expect(screen.getByRole('dialog')).not.toBeNull();
  });

  it('존재하지 않는 시설물이면 에러 메시지를 표시한다', async () => {
    renderPage('/inspections/create?facilityId=999');

    expect(await screen.findByText('시설물 정보를 불러오지 못했습니다.')).not.toBeNull();
  });
});
