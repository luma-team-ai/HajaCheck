// @vitest-environment jsdom
// DefectDetailPage 통합 테스트 — 하드코딩 목데이터 대신 useDefect(id) 실 데이터 조회로 바뀐 것을 검증(HAJA-30).
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import { defectHandlers } from '../api/defectApi.handlers';
import { mockDefects } from '../mocks/defect.mock';
import { DefectDetailPage } from './DefectDetailPage';

const explainHandler = http.post('/api/ai/defect-explain', () =>
  HttpResponse.json({
    success: true,
    data: {
      cause: '구조 응력과 미세한 재료 수축이 복합적으로 작용한 것으로 추정됩니다.',
      risk: '방치 시 균열과 부식이 진행될 수 있습니다.',
      action: '에폭시 주입 후 표면 도포를 권장합니다.',
    },
  }),
);

const server = setupServer(...defectHandlers, explainHandler);
// PATCH 핸들러가 mockDefects를 in-place로 변경한다(재조회 시 최신 상태를 반영하기 위함) — 상태
// 전이 테스트가 이 모듈 싱글턴을 다음 테스트로 오염시키지 않도록 매 테스트 후 스냅샷으로 복원한다.
const mockDefectsSnapshot = JSON.parse(JSON.stringify(mockDefects)) as typeof mockDefects;

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
  mockDefectsSnapshot.forEach((snapshot, index) => {
    Object.assign(mockDefects[index], snapshot);
  });
});
afterAll(() => server.close());

function renderPage(id: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/defects/${id}`]}>
        <Routes>
          <Route path="/defects/:id" element={<DefectDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('DefectDetailPage (통합 테스트)', () => {
  it('실 데이터 조회 성공: 하자 정보(유형/등급/위치/상태)를 렌더링한다', async () => {
    renderPage('1');

    expect(await screen.findByText('철근 노출')).not.toBeNull();
    expect(screen.getByText('D · 경고')).not.toBeNull();
    // 위치는 요약 칩(defect-summary-chips)에 시각적으로 노출되어야 한다 — sr-only에만
    // 남아 화면에서 사라지는 회귀를 잡기 위해 className으로 실제 노출 여부까지 확인한다.
    const locationEl = screen.getByText('강남 오피스타워 A동');
    expect(locationEl.className).toContain('defect-chip');
    // '조치대기'는 상태 요약(dd)과 상태 전이 스텝퍼(HAJA-30 2단계)에 중복 노출되므로 dd로 범위를 좁힌다.
    expect(screen.getByText('상태').nextElementSibling?.textContent).toBe('조치대기');
  });

  it('균열폭/길이 정보가 없으면(id=1, null) "정보 없음"을 표시한다', async () => {
    renderPage('1');

    await screen.findByText('철근 노출');
    expect(screen.getByText('정보 없음')).not.toBeNull();
  });

  it('균열폭/길이 실값이 있으면(id=2) mm 단위와 함께 표시한다', async () => {
    renderPage('2');

    await screen.findByText('균열');
    expect(screen.getByText('1.2mm')).not.toBeNull();
    expect(screen.getByText(/45mm/)).not.toBeNull();
    expect(screen.queryByText('정보 없음')).toBeNull();
  });

  it('존재하지 않는 id: 에러 폴백을 표시한다', async () => {
    renderPage('999999');

    expect(await screen.findByText('하자 정보를 불러오지 못했습니다.')).not.toBeNull();
  });

  it('서버 에러: 에러 폴백을 표시하고 재시도 버튼이 노출된다', async () => {
    server.use(
      http.get('/api/defects/:id', () => {
        const failure: ApiResponse<null> = {
          success: false,
          data: null,
          error: { code: 'INTERNAL_ERROR', message: '서버 오류가 발생했습니다.' },
        };
        return HttpResponse.json(failure, { status: 500 });
      }),
    );

    renderPage('1');

    expect(await screen.findByRole('button', { name: '다시 시도' })).not.toBeNull();
  });

  it('상태 전이 스텝퍼: 다음 단계 버튼 클릭 시 상태가 조치중으로 전이된다', async () => {
    renderPage('1');
    await screen.findByText('철근 노출');

    // id=1 목데이터는 ACTION_PENDING(조치대기) — 다음 단계는 IN_PROGRESS(조치중).
    const advanceButton = await screen.findByRole('button', { name: '조치중(으)로 다음 단계' });
    fireEvent.click(advanceButton);

    await waitFor(() => {
      expect(screen.getByText('상태').nextElementSibling?.textContent).toBe('조치중');
    });
    // 조치중 다음 단계는 해결됨(RESOLVED) — types.ts DEFECT_STATUS_LABEL 매핑과 동일 표기.
    expect(await screen.findByRole('button', { name: '해결됨(으)로 다음 단계' })).not.toBeNull();
  });

  it('실사진: imageUrl이 있으면 이미지를 렌더링한다(HAJA-314)', async () => {
    renderPage('1');

    const img = await screen.findByRole('img', { name: '철근 노출 촬영 이미지' });
    expect((img as HTMLImageElement).src).toContain('/api/media/901/thumbnail');
  });

  it('실사진: imageUrl이 없으면 빈 상태 메시지를 표시한다(HAJA-314)', async () => {
    renderPage('3');

    expect(await screen.findByText('박리·박락')).not.toBeNull();
    expect(screen.getByText('촬영 이미지가 없습니다')).not.toBeNull();
  });

  it('활동 기록: 상태 전이 이력을 렌더링한다(HAJA-314)', async () => {
    renderPage('1');

    await screen.findByText('철근 노출');
    expect(await screen.findByText("상태를 '확인됨'에서 '조치대기'(으)로 변경했습니다.")).not.toBeNull();
  });

  it('상태 전이 스텝퍼: 서버가 409를 반환하면 에러 메시지를 표시한다', async () => {
    server.use(
      http.patch('/api/defects/:id/status', () => {
        const failure: ApiResponse<null> = {
          success: false,
          data: null,
          error: { code: 'INVALID_STATE_TRANSITION', message: '현재 상태에서는 처리할 수 없는 요청입니다.' },
        };
        return HttpResponse.json(failure, { status: 409 });
      }),
    );

    renderPage('1');
    await screen.findByText('철근 노출');

    const advanceButton = await screen.findByRole('button', { name: '조치중(으)로 다음 단계' });
    fireEvent.click(advanceButton);

    expect(await screen.findByText('현재 상태에서는 처리할 수 없는 요청입니다.')).not.toBeNull();
    // 실패했으므로 상태는 그대로 조치대기여야 한다.
    expect(screen.getByText('상태').nextElementSibling?.textContent).toBe('조치대기');
  });
});
