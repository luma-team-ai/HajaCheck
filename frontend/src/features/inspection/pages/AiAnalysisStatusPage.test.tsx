// @vitest-environment jsdom
// AiAnalysisStatusPage 렌더 테스트(코드 리뷰 P2 — 막다른 길 수정) — InspectionCreatePage가
// POST /api/inspections/{id}/analyze 트리거 실패를 조용히 삼키고 이동하면, 이 화면은 stage='upload'
// (분석이 아예 시작된 적 없음, InspectionAnalysisService.rebuildFromDb 참고)로 재구성된 상태를 받는다.
// 예전에는 이 상태에서 분석을 시작/재시도할 버튼이 전혀 없어(취소·검수시작 모두 disabled) 사용자가
// 화면을 이탈하는 것 말고는 빠져나갈 길이 없었다 — "분석 시작" 버튼이 보이고 클릭 시 실제로
// POST /analyze를 재호출하는지 고정한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { createMemoryRouter, Link, RouterProvider } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { inspectionApi } from '../api/inspectionApi';
import type { AnalysisStatusResponse } from '../api/inspectionApi.types';
import { AiAnalysisStatusPage, computeSeverityPercentages } from './AiAnalysisStatusPage';

const server = setupServer();

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
  vi.restoreAllMocks();
});
afterAll(() => server.close());

function preAnalysisStatus(): AnalysisStatusResponse {
  return {
    inspectionId: 100,
    stage: 'upload',
    progressPercent: 0,
    totalFileCount: 1,
    analyzedFileCount: 0,
    files: [{ mediaId: 1, fileName: '이미지 1', status: 'waiting', defectCount: null, elapsedOrEta: '-' }],
    detectedDefectCount: 0,
    riskyCrackCount: 0,
    severityDistribution: { A: 0, B: 0, C: 0, D: 0, E: 0 },
    failedCount: 0,
    unanalyzedMediaCount: 1,
  };
}

function analyzingStatus(): AnalysisStatusResponse {
  return {
    inspectionId: 100,
    stage: 'aiDetection',
    progressPercent: 50,
    totalFileCount: 2,
    analyzedFileCount: 1,
    files: [
      { mediaId: 1, fileName: '이미지 1', status: 'completed', defectCount: 0, elapsedOrEta: '1.0s' },
      { mediaId: 2, fileName: '이미지 2', status: 'analyzing', defectCount: null, elapsedOrEta: '처리중...' },
    ],
    detectedDefectCount: 0,
    riskyCrackCount: 0,
    severityDistribution: { A: 0, B: 0, C: 0, D: 0, E: 0 },
    failedCount: 0,
    unanalyzedMediaCount: 1,
  };
}

function doneStatus(unanalyzedMediaCount: number): AnalysisStatusResponse {
  return {
    inspectionId: 100,
    stage: 'done',
    progressPercent: 100,
    totalFileCount: 2,
    analyzedFileCount: 2 - unanalyzedMediaCount,
    files: [
      { mediaId: 1, fileName: '이미지 1', status: 'completed', defectCount: 0, elapsedOrEta: '1.0s' },
      {
        mediaId: 2,
        fileName: '이미지 2',
        status: unanalyzedMediaCount > 0 ? 'waiting' : 'completed',
        defectCount: unanalyzedMediaCount > 0 ? null : 0,
        elapsedOrEta: unanalyzedMediaCount > 0 ? '-' : '1.2s',
      },
    ],
    detectedDefectCount: 0,
    riskyCrackCount: 0,
    severityDistribution: { A: 0, B: 0, C: 0, D: 0, E: 0 },
    failedCount: 0,
    unanalyzedMediaCount,
  };
}

// useBlocker(react-router-dom)는 data router(createMemoryRouter/RouterProvider) 컨텍스트 안에서만
// 동작한다(InspectionCreatePage.test.tsx와 동일 이유) — "대시보드로 이동" Link는 사이드바 클릭을
// 대역하는 테스트 전용 라우트. 이동 여부는 router.state.location.pathname으로 직접 확인한다 — 목적지
// 라우트로 전환되면 이 route의 element(따라서 그 안의 아무 컴포넌트든)가 통째로 언마운트되므로,
// LocationProbe를 이 element 안에 두면 이동 성공 자체를 검증할 수 없다(InspectionCreatePage.test.tsx와
// 동일 패턴 — router.state를 직접 읽는다).
function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });

  const router = createMemoryRouter(
    [
      {
        path: '/inspections/:id/analysis',
        element: (
          <>
            <Link to="/dashboard">대시보드로 이동(사이드바 대역)</Link>
            <AiAnalysisStatusPage />
          </>
        ),
      },
      { path: '/dashboard', element: <div>대시보드 페이지</div> },
    ],
    { initialEntries: ['/inspections/100/analysis'] },
  );

  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return router;
}

describe('AiAnalysisStatusPage', () => {
  it('realMode·stage=upload(분석 미시작)이면 분석 시작 버튼이 보이고, 클릭 시 startAnalysis를 호출한다', async () => {
    server.use(
      http.get('/api/inspections/:id/analyze', () =>
        HttpResponse.json({ success: true, data: preAnalysisStatus() }),
      ),
      http.post('/api/inspections/:id/analyze', () => new HttpResponse(null, { status: 202 })),
    );
    const startAnalysisSpy = vi.spyOn(inspectionApi, 'startAnalysis');

    renderPage();

    const startButton = await screen.findByRole('button', { name: '분석 시작' });
    // 취소/검수시작 버튼이 아니라 "분석 시작" 하나만 노출돼야 한다 — 막다른 길이 아니라는 뜻.
    expect(screen.queryByRole('button', { name: '분석 취소' })).toBeNull();
    expect(screen.queryByRole('button', { name: /검수 시작/ })).toBeNull();

    await act(async () => {
      fireEvent.click(startButton);
    });

    await waitFor(() => expect(startAnalysisSpy).toHaveBeenCalledWith(100));
  });

  it('분석 시작 트리거 자체가 다시 실패하면 서버가 준 구체 메시지가 배너에 뜨고 화면이 막다른 길이 되지 않는다', async () => {
    // 코드 리뷰 P3(해소) — handleRetry가 getApiErrorMessage(shared/api/types.ts)로 바뀌어, axios
    // 인터셉터가 던지는 ApiError(Error 서브클래스가 아님)에서도 서버 메시지를 그대로 뽑아 보여준다.
    server.use(
      http.get('/api/inspections/:id/analyze', () =>
        HttpResponse.json({ success: true, data: preAnalysisStatus() }),
      ),
      http.post('/api/inspections/:id/analyze', () => {
        const failure = {
          success: false,
          data: null,
          error: { code: 'ANALYSIS_NO_MEDIA', message: '업로드된 이미지가 없습니다.' },
        };
        return HttpResponse.json(failure, { status: 400 });
      }),
    );

    renderPage();

    const startButton = await screen.findByRole('button', { name: '분석 시작' });
    await act(async () => {
      fireEvent.click(startButton);
    });

    expect(await screen.findByText('업로드된 이미지가 없습니다.')).not.toBeNull();
    // 버튼은 여전히 남아있어야 재시도가 가능하다 — 실패했다고 화면이 막히면 안 된다.
    expect(screen.getByRole('button', { name: '분석 시작' })).not.toBeNull();
  });

  it('진행률 요약 옆에 점검 ID를 노출한다 — 관리자가 진행/완료 여부를 추적할 식별자', async () => {
    server.use(
      http.get('/api/inspections/:id/analyze', () =>
        HttpResponse.json({ success: true, data: analyzingStatus() }),
      ),
    );

    renderPage();

    expect(await screen.findByText('점검 ID #100', { exact: false })).not.toBeNull();
  });

  describe('증분 분석(#1654) — 분석 완료 후 추가 업로드된 원본 사진', () => {
    it('done이고 unanalyzedMediaCount>0이면 "추가 사진 N장 분석" 버튼이 보이고, 클릭 시 startAnalysis를 재호출한다', async () => {
      // 배경: 분석 완료(ANALYZED) 후 추가 업로드된 원본 사진이 재분석 fail-closed 가드에 막혀
      // 영구 미분석으로 남던 버그(운영 실사례 m473/m474 등). 백엔드가 미분석 사진 유무로 증분
      // 여부를 자동 판단하므로, 프론트는 기존 handleRetry(POST /analyze 재호출)를 그대로 재사용한다.
      server.use(
        http.get('/api/inspections/:id/analyze', () =>
          HttpResponse.json({ success: true, data: doneStatus(1) }),
        ),
        http.post('/api/inspections/:id/analyze', () => new HttpResponse(null, { status: 202 })),
      );
      const startAnalysisSpy = vi.spyOn(inspectionApi, 'startAnalysis');

      renderPage();

      const incrementalButton = await screen.findByRole('button', { name: '추가 사진 1장 분석' });
      // 이미 분석이 끝났으므로 "검수 시작"도 함께 활성화돼 있어야 한다 — 증분 액션이 막다른 길을
      // 만들지 않는다.
      expect(screen.getByRole('button', { name: '검수 시작' })).not.toBeNull();

      await act(async () => {
        fireEvent.click(incrementalButton);
      });

      await waitFor(() => expect(startAnalysisSpy).toHaveBeenCalledWith(100));
    });

    it('done이고 unanalyzedMediaCount=0이면 "추가 사진 분석" 버튼이 보이지 않는다', async () => {
      server.use(
        http.get('/api/inspections/:id/analyze', () =>
          HttpResponse.json({ success: true, data: doneStatus(0) }),
        ),
      );

      renderPage();

      await screen.findByRole('button', { name: '검수 시작' });
      expect(screen.queryByRole('button', { name: /추가 사진/ })).toBeNull();
    });
  });

  describe('이탈해도 안전하게 계속 진행(정책 변경, 2026-07-28) — 분석 진행 중 이탈/취소', () => {
    it('분석이 진행 중이어도 다른 라우트로 이동하면 확인창 없이 바로 이동하고, 분석 취소(DELETE)는 호출되지 않는다', async () => {
      let cancelCallCount = 0;
      server.use(
        http.get('/api/inspections/:id/analyze', () =>
          HttpResponse.json({ success: true, data: analyzingStatus() }),
        ),
        http.delete('/api/inspections/:id/analyze', () => {
          cancelCallCount += 1;
          return new HttpResponse(null, { status: 204 });
        }),
      );

      const router = renderPage();
      await screen.findByText('50%');

      fireEvent.click(screen.getByText('대시보드로 이동(사이드바 대역)'));

      expect(screen.queryByText('분석이 진행 중입니다')).toBeNull();
      await waitFor(() => expect(router.state.location.pathname).toBe('/dashboard'));
      expect(cancelCallCount).toBe(0);
    });

    it('분석이 아직 시작 전(stage=upload)이어도 이탈은 동일하게 확인창 없이 바로 된다', async () => {
      server.use(
        http.get('/api/inspections/:id/analyze', () =>
          HttpResponse.json({ success: true, data: preAnalysisStatus() }),
        ),
      );

      renderPage();
      await screen.findByRole('button', { name: '분석 시작' });

      fireEvent.click(screen.getByText('대시보드로 이동(사이드바 대역)'));

      expect(screen.queryByText('분석이 진행 중입니다')).toBeNull();
      expect(await screen.findByText('대시보드 페이지')).not.toBeNull();
    });

    it('"분석 취소" 버튼을 누르면 취소 후 재조회해 분석 시작 전 화면으로 돌아온다', async () => {
      let hasCancelled = false;
      server.use(
        http.get('/api/inspections/:id/analyze', () =>
          HttpResponse.json({
            success: true,
            data: hasCancelled ? preAnalysisStatus() : analyzingStatus(),
          }),
        ),
        http.delete('/api/inspections/:id/analyze', () => {
          hasCancelled = true;
          return new HttpResponse(null, { status: 204 });
        }),
      );

      renderPage();
      const cancelButton = await screen.findByRole('button', { name: '분석 취소' });

      await act(async () => {
        fireEvent.click(cancelButton);
      });

      expect(await screen.findByRole('button', { name: '분석 시작' })).not.toBeNull();
    });
  });
});

describe('computeSeverityPercentages (코드 리뷰 P3 — 반올림 합계 100% 보장)', () => {
  it('1/1/1건처럼 33.33%씩 나뉘어도(옛 로직이면 99%) 합계가 정확히 100이다', () => {
    const result = computeSeverityPercentages({ A: 1, B: 1, C: 1, D: 0, E: 0 });

    const sum = Object.values(result).reduce((a, b) => a + b, 0);
    expect(sum).toBe(100);
    // 나머지가 전부 0.333...으로 동률이라 A→E 순서로 결정론적으로 1%씩 배분된다.
    expect(result).toEqual({ A: 34, B: 33, C: 33, D: 0, E: 0 });
  });

  it('total이 0이면(분석 시작 전) 전부 0을 반환한다', () => {
    expect(computeSeverityPercentages({ A: 0, B: 0, C: 0, D: 0, E: 0 })).toEqual({
      A: 0,
      B: 0,
      C: 0,
      D: 0,
      E: 0,
    });
  });

  it('한 등급에만 몰려 있으면 그 등급이 100, 나머지는 0이다', () => {
    expect(computeSeverityPercentages({ A: 0, B: 0, C: 0, D: 0, E: 7 })).toEqual({
      A: 0,
      B: 0,
      C: 0,
      D: 0,
      E: 100,
    });
  });

  it('임의의 분포를 여러 개 넣어도 합계는 항상 100이다(회귀 방지)', () => {
    const distributions: Record<'A' | 'B' | 'C' | 'D' | 'E', number>[] = [
      { A: 2, B: 3, C: 5, D: 7, E: 11 },
      { A: 1, B: 0, C: 0, D: 0, E: 2 },
      { A: 10, B: 10, C: 10, D: 10, E: 1 },
      { A: 6, B: 6, C: 6, D: 1, E: 1 },
      { A: 100, B: 1, C: 1, D: 1, E: 1 },
    ];

    for (const dist of distributions) {
      const result = computeSeverityPercentages(dist);
      const sum = Object.values(result).reduce((a, b) => a + b, 0);
      expect(sum).toBe(100);
    }
  });
});
