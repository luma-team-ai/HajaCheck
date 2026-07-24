// @vitest-environment jsdom
// FacilityListPage 통합 테스트 — 실제 useFacilities/useCreateFacility 훅 + MSW facilityHandlers를 통해
// "등록 성공 시 목록 반영(invalidateQueries)"과 "등록 실패 시 모달 유지·폼 값 보존"을 검증한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import { facilityHandlers, resetFacilityMockStore } from '../api/facilityApi.handlers';
import { FacilityListPage } from './FacilityListPage';

const server = setupServer(...facilityHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  // 모듈 스코프 목 저장소(facilities/nextId)는 resetHandlers()로 초기화되지 않으므로,
  // 한 테스트에서 등록한 시설물이 다음 테스트의 목록에 새지 않도록 명시적으로 리셋한다.
  resetFacilityMockStore();
  cleanup();
});
afterAll(() => server.close());

function renderPage(): void {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/facilities/list']}>
        <Routes>
          <Route path="/facilities/list" element={<FacilityListPage />} />
          <Route path="/facilities/:id" element={<div>시설물 상세 화면</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function openCreateModal() {
  fireEvent.click(screen.getByRole('button', { name: '+ 시설물 등록' }));
}

function fillRequiredFields(name: string) {
  fireEvent.change(screen.getByLabelText(/시설물명/), { target: { value: name } });
  // #731 — 유형 옵션이 조합형 12종으로 확장돼 단순 '건물'은 더 이상 유효한 <option>이 아니다.
  fireEvent.change(screen.getByLabelText(/시설물 유형/), {
    target: { value: '건물-정기-4개월' },
  });
}

describe('FacilityListPage (통합 테스트)', () => {
  it('초기 목록: MSW 목 데이터를 불러와 테이블에 렌더링한다', async () => {
    renderPage();

    expect(await screen.findByText('강남 오피스타워 A동')).not.toBeNull();
  });

  it('등록 성공: 새 시설물이 목록에 즉시 반영되고 모달이 닫힌다', async () => {
    renderPage();
    await screen.findByText('강남 오피스타워 A동');

    openCreateModal();
    fillRequiredFields('테스트 신규 시설물');

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '등록하기' }));
    });

    expect(await screen.findByText('테스트 신규 시설물')).not.toBeNull();
    // 등록 성공 후 모달이 닫혀 더 이상 폼이 렌더링되지 않는다
    expect(screen.queryByRole('dialog')).toBeNull();
    // #731 — 유형 "건물-정기-4개월" 선택으로 파생된 점검주기(4개월)가 MSW 목 POST 응답을 거쳐
    // 목록 테이블의 "점검주기" 컬럼까지 그대로 반영돼야 한다(등록 폼 → 요청 → 목 저장소 → 렌더링 전체 경로).
    expect(await screen.findByText('4개월')).not.toBeNull();
  });

  it('등록 실패: 모달이 닫히지 않고 입력한 폼 값이 유지되며 에러 메시지가 표시된다', async () => {
    server.use(
      http.post('/api/facilities', () => {
        const failure: ApiResponse<null> = {
          success: false,
          data: null,
          error: { code: 'FACILITY_CREATE_FAILED', message: '시설물 등록에 실패했습니다.' },
        };
        return HttpResponse.json(failure, { status: 400 });
      }),
    );

    renderPage();
    await screen.findByText('강남 오피스타워 A동');

    openCreateModal();
    fillRequiredFields('실패할 시설물');

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '등록하기' }));
    });

    expect(await screen.findByText('시설물 등록에 실패했습니다.')).not.toBeNull();
    // 모달은 여전히 열려 있고, 입력값도 초기화되지 않아야 한다
    expect(screen.queryByRole('dialog')).not.toBeNull();
    expect((screen.getByLabelText(/시설물명/) as HTMLInputElement).value).toBe('실패할 시설물');
  });

  it('시설물 이름 클릭 시 /facilities/:id(하자 정보 패널)로 이동한다(#489)', async () => {
    renderPage();
    await screen.findByText('강남 오피스타워 A동');

    fireEvent.click(screen.getByRole('button', { name: '강남 오피스타워 A동' }));

    expect(await screen.findByText('시설물 상세 화면')).not.toBeNull();
  });
});