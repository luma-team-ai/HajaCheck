// @vitest-environment jsdom
// InspectionFilterBar 통합 테스트 — #726/HAJA-394(백엔드 PR #891)로 점검 목록에 자연어(하자조건)
// 검색이 추가된 것을 검증한다(InspectionNlSearchBar 통합 + 하자조건 필터 칩).
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { defectHandlers } from '../api/defectApi.handlers';
import type { InspectionListFilters } from '../types';
import { InspectionFilterBar } from './InspectionFilterBar';

const server = setupServer(...defectHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

function renderFilterBar(filters: InspectionListFilters, onChange = vi.fn()) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <InspectionFilterBar filters={filters} onChange={onChange} />
    </QueryClientProvider>,
  );
  return { onChange };
}

describe('InspectionFilterBar — 하자조건 필터 칩', () => {
  it('defectType/defectGrade/defectStatus가 있으면 값들을 join한 칩 하나씩 표시한다', async () => {
    renderFilterBar({
      defectType: ['CRACK', 'SPALLING'],
      defectGrade: ['D', 'E'],
      defectStatus: ['CONFIRMED'],
      page: 0,
      size: 10,
    });

    await screen.findByText('적용된 필터:');
    expect(
      screen.getByRole('button', { name: '하자유형: 균열, 박리·박락 필터 제거' }),
    ).not.toBeNull();
    expect(
      screen.getByRole('button', { name: '하자등급: 경고, 중대 필터 제거' }),
    ).not.toBeNull();
    expect(
      screen.getByRole('button', { name: '하자상태: 확인됨 필터 제거' }),
    ).not.toBeNull();
  });

  it('빈 배열이면 해당 차원의 칩을 표시하지 않는다', () => {
    renderFilterBar({ defectType: [], page: 0, size: 10 });

    expect(screen.queryByText('적용된 필터:')).toBeNull();
  });

  it('하자조건 칩을 제거하면 해당 배열 전체를 undefined로 초기화한다', async () => {
    const { onChange } = renderFilterBar({
      defectGrade: ['D', 'E'],
      page: 0,
      size: 10,
    });

    fireEvent.click(screen.getByRole('button', { name: '하자등급: 경고, 중대 필터 제거' }));

    expect(onChange).toHaveBeenCalledWith({
      defectGrade: undefined,
      page: 0,
      size: 10,
    });
  });

  it('기존 status/facilityId 칩과 하자조건 칩이 함께 표시된다', async () => {
    renderFilterBar({
      status: 'REPORTED',
      defectStatus: ['RESOLVED'],
      page: 0,
      size: 10,
    });

    await screen.findByText('적용된 필터:');
    expect(screen.getByRole('button', { name: '상태: 보고완료 필터 제거' })).not.toBeNull();
    expect(
      screen.getByRole('button', { name: '하자상태: 해결됨 필터 제거' }),
    ).not.toBeNull();
  });
});

describe('InspectionFilterBar — 자연어(하자조건) 검색', () => {
  it('정상 질의는 인식된 하자조건 배열을 그대로 필터에 반영한다', async () => {
    const { onChange } = renderFilterBar({ page: 0, size: 20 });

    fireEvent.change(screen.getByLabelText('AI 자연어 검색'), {
      target: { value: 'D등급 이상 조치 대기 하자가 있는 점검' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'AI 검색 실행' }));

    await waitFor(() =>
      expect(onChange).toHaveBeenCalledWith({
        page: 0,
        size: 20,
        defectType: [],
        defectGrade: ['D', 'E'],
        defectStatus: ['CONFIRMED'],
      }),
    );
  });

  it('되묻는 질문이 오면 필터를 적용하지 않고 질문만 보여준다', async () => {
    const { onChange } = renderFilterBar({ page: 0, size: 20 });

    fireEvent.change(screen.getByLabelText('AI 자연어 검색'), {
      target: { value: '점검 좀 보여줘' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'AI 검색 실행' }));

    expect(
      await screen.findByText('어떤 유형·등급·상태의 하자를 찾으시나요?'),
    ).not.toBeNull();
    expect(onChange).not.toHaveBeenCalled();
  });

  it('unsupported_terms는 안내 문구로만 노출하고 필터는 적용하지 않는다', async () => {
    server.use(
      http.post('/api/defects/nl-search', () =>
        HttpResponse.json({
          success: true,
          data: {
            filters: { type: [], grade: [], status: [], confidenceMin: null },
            unsupported_terms: ['지하주차장'],
            clarifying_question: null,
            interpretation_confidence: 0.9,
          },
        }),
      ),
    );
    const { onChange } = renderFilterBar({ page: 0, size: 20 });

    fireEvent.change(screen.getByLabelText('AI 자연어 검색'), {
      target: { value: '지하주차장 점검 보여줘' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'AI 검색 실행' }));

    expect(
      await screen.findByText('다음 조건은 아직 지원하지 않아 제외했어요: 지하주차장'),
    ).not.toBeNull();
    expect(onChange).not.toHaveBeenCalled();
  });

  it('confidenceMin 인식 조건은 적용 없이 안내만 한다(GET /api/inspections 미지원 필드)', async () => {
    server.use(
      http.post('/api/defects/nl-search', () =>
        HttpResponse.json({
          success: true,
          data: {
            filters: { type: [], grade: [], status: [], confidenceMin: 0.8 },
            unsupported_terms: [],
            clarifying_question: null,
            interpretation_confidence: 0.9,
          },
        }),
      ),
    );
    const { onChange } = renderFilterBar({ page: 0, size: 20 });

    fireEvent.change(screen.getByLabelText('AI 자연어 검색'), {
      target: { value: '신뢰도 80% 이상인 점검만 보여줘' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'AI 검색 실행' }));

    expect(
      await screen.findByText(
        '신뢰도 80% 이상 조건은 아직 점검 목록 필터에 적용할 수 없어 제외했어요',
      ),
    ).not.toBeNull();
    expect(onChange).not.toHaveBeenCalled();
  });

  it('적용 가능한 조건이 0건이면(전부 빈 배열) 기존 필터를 유지하고 onChange를 호출하지 않는다', async () => {
    let handlerCalled = false;
    server.use(
      http.post('/api/defects/nl-search', () => {
        handlerCalled = true;
        return HttpResponse.json({
          success: true,
          data: {
            filters: { type: [], grade: [], status: [], confidenceMin: null },
            unsupported_terms: [],
            clarifying_question: null,
            interpretation_confidence: 0.9,
          },
        });
      }),
    );
    // 사전 status 필터(REPORTED)가 있는 상태에서 전 필드 빈 배열 응답이 와도 조용히 날리지 않는다.
    const { onChange } = renderFilterBar({ status: 'REPORTED', page: 0, size: 20 });

    fireEvent.change(screen.getByLabelText('AI 자연어 검색'), {
      target: { value: '아무거나' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'AI 검색 실행' }));

    await waitFor(() => expect(handlerCalled).toBe(true));
    // 기존 status 필터는 그대로 남아 칩으로 표시된다(applied filters는 props로부터만 파생됨).
    expect(await screen.findByRole('button', { name: '상태: 보고완료 필터 제거' })).not.toBeNull();
    expect(onChange).not.toHaveBeenCalled();
  });

  it('AI_ADDON_REQUIRED 실패 시 업그레이드 안내를 보여주고 기존 필터를 유지한다', async () => {
    server.use(
      http.post('/api/defects/nl-search', () =>
        HttpResponse.json(
          { success: false, data: null, error: { code: 'AI_ADDON_REQUIRED', message: '플랜 제한' } },
          { status: 403 },
        ),
      ),
    );
    const { onChange } = renderFilterBar({ page: 0, size: 20 });

    fireEvent.change(screen.getByLabelText('AI 자연어 검색'), {
      target: { value: '균열만 보여줘' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'AI 검색 실행' }));

    expect(
      await screen.findByText('AI 자연어 검색은 AI 부가 기능이 포함된 플랜에서만 사용할 수 있습니다.'),
    ).not.toBeNull();
    expect(onChange).not.toHaveBeenCalled();
  });
});

describe('InspectionFilterBar — 기존 상태/시설물 필터 + 초기화(회귀)', () => {
  it('select로 상태 필터를 직접 설정할 수 있다', () => {
    const { onChange } = renderFilterBar({ page: 0, size: 10 });

    fireEvent.change(screen.getByRole('combobox', { name: '점검 상태 필터' }), {
      target: { value: 'REPORTED' },
    });

    expect(onChange).toHaveBeenCalledWith({ status: 'REPORTED', page: 0, size: 10 });
  });

  it('초기화 버튼은 하자조건 필터를 포함한 전체 필터를 지운다', () => {
    const { onChange } = renderFilterBar({
      status: 'REPORTED',
      defectType: ['CRACK'],
      defectGrade: ['D'],
      defectStatus: ['RESOLVED'],
      page: 3,
      size: 10,
    });

    fireEvent.click(screen.getByRole('button', { name: '필터 초기화' }));

    expect(onChange).toHaveBeenCalledWith({ page: 0, size: 10 });
  });
});
