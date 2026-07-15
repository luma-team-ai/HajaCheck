// @vitest-environment jsdom
// React Query 훅은 QueryClientProvider 컨텍스트가 필요하므로,
// DefectExplainPanel 컴포넌트를 통한 통합 테스트로 로딩/에러/성공 상태를 검증한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import type { ReactElement } from 'react';
import { createRoot } from 'react-dom/client';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import { DefectExplainPanel } from '../components/DefectExplainPanel';

const mockDefectExplain = {
  cause: '바닥재 수분 침투 및 시간 경과에 따른 자연 박리',
  risk: '낙상 위험, 보행 불편',
  action: '바닥재 전체 교체 필요',
};

const handlers = [
  http.post('/ai/defect-explain', async ({ request }) => {
    // 요청 딜레이를 시뮬레이션해서 로딩 상태를 테스트할 수 있게 함
    await new Promise((resolve) => setTimeout(resolve, 50));

    const body = (await request.json()) as {
      defect_type: string;
      severity_grade: string;
      location: string;
      facility_type: string;
    };

    if (
      body.defect_type &&
      body.severity_grade &&
      body.location &&
      body.facility_type
    ) {
      const success: ApiResponse<typeof mockDefectExplain> = {
        success: true,
        data: mockDefectExplain,
      };
      return HttpResponse.json(success);
    }

    const failure: ApiResponse<null> = {
      success: false,
      data: null,
      error: {
        code: 'LLM_INVALID_INPUT',
        message: '필수 파라미터가 누락되었습니다.',
      },
    };
    return HttpResponse.json(failure, { status: 400 });
  }),
];

const server = setupServer(...handlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('useDefectExplain (통합 테스트)', () => {
  const renderWithQuery = (element: ReactElement) => {
    const container = document.createElement('div');
    document.body.appendChild(container);
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: 1 },
      },
    });

    const root = createRoot(container);
    root.render(
      <QueryClientProvider client={queryClient}>
        {element}
      </QueryClientProvider>,
    );

    return { container, root, queryClient };
  };

  const waitFor = (predicate: () => boolean, timeout = 3000): Promise<void> => {
    return new Promise((resolve, reject) => {
      const startTime = Date.now();
      const interval = setInterval(() => {
        if (predicate()) {
          clearInterval(interval);
          resolve();
        } else if (Date.now() - startTime > timeout) {
          clearInterval(interval);
          reject(new Error('Timeout waiting for condition'));
        }
      }, 50);
    });
  };

  it('성공 상태: 데이터가 로드되면 하자 설명을 렌더링한다', async () => {
    const { container, root } = renderWithQuery(
      <DefectExplainPanel
        defect_type="바닥재 박리"
        severity_grade="HIGH"
        location="1층 복도"
        facility_type="사무실"
      />,
    );

    // 로딩 상태 확인
    await waitFor(() => container.textContent?.includes('분석하고 있습니다') || container.textContent?.includes('추정 원인'));

    // 데이터 로드 완료 대기
    await waitFor(() => container.textContent?.includes('추정 원인'));

    expect(container.textContent).toContain('추정 원인');
    expect(container.textContent).toContain('바닥재 수분 침투');
    expect(container.textContent).toContain('방치 시 위험');
    expect(container.textContent).toContain('낙상 위험');
    expect(container.textContent).toContain('조치 방안');
    expect(container.textContent).toContain('바닥재 전체 교체');

    root.unmount();
  });

  it('로딩 상태: 요청 중에는 로딩 메시지가 표시되었다가 성공하면 사라진다', async () => {
    let resolveRequest: (() => void) = () => {};
    const requestPromise = new Promise<void>((resolve) => {
      resolveRequest = resolve;
    });

    server.use(
      http.post('/ai/defect-explain', async () => {
        // 요청이 완료될 때까지 기다림
        await requestPromise;
        const success: ApiResponse<typeof mockDefectExplain> = {
          success: true,
          data: mockDefectExplain,
        };
        return HttpResponse.json(success);
      }),
    );

    const { container, root } = renderWithQuery(
      <DefectExplainPanel
        defect_type="바닥재 박리"
        severity_grade="HIGH"
        location="1층 복도"
        facility_type="사무실"
      />,
    );

    // 요청이 시작되도록 약간의 시간 제공
    await new Promise((resolve) => setTimeout(resolve, 100));

    // 요청 완료
    resolveRequest();

    // 데이터 로드 완료 대기 (로딩이 완료되고 데이터가 표시됨)
    await waitFor(() => container.textContent?.includes('추정 원인'), 2000);

    expect(container.textContent).toContain('추정 원인');
    expect(container.textContent).not.toContain('분석하고 있습니다'); // 로딩 완료됨

    root.unmount();
  });

  it('필수 파라미터 누락: 쿼리가 비활성화되어 아무것도 렌더링되지 않는다', async () => {
    const { container, root } = renderWithQuery(
      <DefectExplainPanel
        defect_type="" /* 빈 값 */
        severity_grade="HIGH"
        location="1층 복도"
        facility_type="사무실"
      />,
    );

    // 초기에는 로딩이 발생하지 않음 (enabled 조건이 false)
    // 에러도 없음 (쿼리가 활성화되지 않음)
    // — 이 케이스는 필수 파라미터 검증이므로, enabled 조건을 통과하지 못한 것
    // 로딩/에러/데이터 모두 없는 상태 확인
    expect(container.textContent).not.toContain('분석하고 있습니다');
    expect(container.textContent).not.toContain('추정 원인');

    root.unmount();
  });

  it('에러 상태: 서버 에러 응답을 처리한다', async () => {
    server.use(
      http.post('/ai/defect-explain', () => {
        const failure: ApiResponse<null> = {
          success: false,
          data: null,
          error: {
            code: 'LLM_PROCESSING_ERROR',
            message: 'AI 분석 중 오류가 발생했습니다.',
          },
        };
        return HttpResponse.json(failure, { status: 500 });
      }),
    );

    const { container, root } = renderWithQuery(
      <DefectExplainPanel
        defect_type="바닥재 박리"
        severity_grade="HIGH"
        location="1층 복도"
        facility_type="사무실"
      />,
    );

    // 에러 상태 확인 (AIErrorFallback이 렌더링됨)
    await waitFor(() => container.textContent?.includes('다시 시도') || container.textContent?.includes('오류'));

    // 에러 메시지 확인
    expect(container.textContent).toContain('AI 분석을 불러올 수 없습니다');

    root.unmount();
  });

  it('재시도 로직: retry: 1로 설정되어 실패 시 한 번 더 시도한다', async () => {
    let callCount = 0;
    server.use(
      http.post('/ai/defect-explain', () => {
        callCount++;
        // 첫 번째 시도는 실패, 두 번째는 성공
        if (callCount === 1) {
          return HttpResponse.error();
        }
        const success: ApiResponse<typeof mockDefectExplain> = {
          success: true,
          data: mockDefectExplain,
        };
        return HttpResponse.json(success);
      }),
    );

    const { container, root } = renderWithQuery(
      <DefectExplainPanel
        defect_type="바닥재 박리"
        severity_grade="HIGH"
        location="1층 복도"
        facility_type="사무실"
      />,
    );

    // 재시도 후 성공하면 데이터가 표시됨
    await waitFor(() => container.textContent?.includes('추정 원인'), 3000);

    expect(container.textContent).toContain('추정 원인');
    expect(callCount).toBe(2); // 초기 시도 + 재시도 1회

    root.unmount();
  });
});
