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
import { MemoryRouter, Route, Routes } from 'react-router-dom';
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
  };
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });

  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/inspections/100/analysis']}>
        <Routes>
          <Route path="/inspections/:id/analysis" element={<AiAnalysisStatusPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
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

  it('분석 시작 트리거 자체가 다시 실패해도 에러 배너를 보여주고 화면이 막다른 길이 되지 않는다', async () => {
    // 주의: handleRetry의 `error instanceof Error` 체크는 axios 인터셉터(shared/api/axios.ts)가
    // 실패 시 던지는 평범한 ApiError 객체(Error 서브클래스가 아님)에는 항상 false라서, 서버가 준
    // 구체적 메시지(예: '업로드된 이미지가 없습니다.')가 아니라 대체 문구만 뜬다 — 이건 이 페이지뿐
    // 아니라 ResultViewerPage 등 여러 곳에 퍼진 기존 버그라 이번 P2(버튼 부재) 수정 범위 밖으로
    // 남겨두고, 여기서는 "적어도 에러 배너는 뜨고 재시도할 수 있다"만 고정한다.
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

    expect(await screen.findByText('재시도에 실패했습니다.')).not.toBeNull();
    // 버튼은 여전히 남아있어야 재시도가 가능하다 — 실패했다고 화면이 막히면 안 된다.
    expect(screen.getByRole('button', { name: '분석 시작' })).not.toBeNull();
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
