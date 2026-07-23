// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { defectHandlers } from "../api/defectApi.handlers";
import type { DefectListFilters } from "../types";
import { DefectFilterBar } from "./DefectFilterBar";

const server = setupServer(...defectHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

function renderFilterBar(filters: DefectListFilters, onChange = vi.fn()) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <DefectFilterBar filters={filters} onChange={onChange} />
    </QueryClientProvider>,
  );
  return { onChange };
}

describe("DefectFilterBar", () => {
  it("LLM이 분석한 값만 결과 칩으로 표시한다", () => {
    renderFilterBar({
      type: "CRACK",
      grade: "D",
      status: "ACTION_PENDING",
      page: 0,
      size: 10,
    });

    expect(
      screen.getByText("질문을 3개의 검색 조건으로 적용했어요"),
    ).not.toBeNull();
    expect(
      screen.getByRole("button", { name: "유형: 균열 필터 제거" }),
    ).not.toBeNull();
    expect(
      screen.getByRole("button", { name: "등급: D 이상 필터 제거" }),
    ).not.toBeNull();
    expect(
      screen.getByRole("button", { name: "상태: 조치대기 필터 제거" }),
    ).not.toBeNull();
    const typeSelect = screen.getByRole(
      "combobox",
      { name: "유형 필터" },
    ) as HTMLSelectElement;
    expect(typeSelect.value).toBe("CRACK");
  });

  it("select로 유형 필터를 직접 설정할 수 있다", () => {
    const { onChange } = renderFilterBar({ page: 0, size: 20 });

    fireEvent.change(screen.getByRole("combobox", { name: "유형 필터" }), {
      target: { value: "CRACK" },
    });

    expect(onChange).toHaveBeenCalledWith({
      type: "CRACK",
      page: 0,
      size: 20,
    });
  });

  it("개별 필터 제거와 전체 초기화를 상위 상태로 전달한다", () => {
    const { onChange } = renderFilterBar({ type: "CRACK", grade: "D", page: 2, size: 20 });

    fireEvent.click(
      screen.getByRole("button", { name: "유형: 균열 필터 제거" }),
    );
    expect(onChange).toHaveBeenNthCalledWith(1, {
      type: undefined,
      grade: "D",
      page: 0,
      size: 20,
    });

    fireEvent.click(screen.getByRole("button", { name: "필터 초기화" }));
    expect(onChange).toHaveBeenNthCalledWith(2, { page: 0, size: 20 });
  });

  // ── AI 자연어 검색 연동(HAJA-120) ──

  it("정상 질의는 반환된 필터로 기존 상태를 교체한다", async () => {
    const { onChange } = renderFilterBar({ page: 0, size: 20 });

    fireEvent.change(screen.getByLabelText("AI 자연어 검색"), {
      target: { value: "D등급 이상 조치 대기 하자" },
    });
    fireEvent.click(screen.getByRole("button", { name: "AI 검색 실행" }));

    await waitFor(() =>
      expect(onChange).toHaveBeenCalledWith({
        page: 0,
        size: 20,
        type: undefined,
        grade: "D",
        status: "ACTION_PENDING",
      }),
    );
    expect(screen.queryByText(/등급 E/)).toBeNull();
  });

  it("confidenceMin 인식 조건은 적용 없이 안내만 한다", async () => {
    server.use(
      http.post("/api/defects/nl-search", () =>
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
    renderFilterBar({ page: 0, size: 20 });

    fireEvent.change(screen.getByLabelText("AI 자연어 검색"), {
      target: { value: "신뢰도 80% 이상인 하자만 보여줘" },
    });
    fireEvent.click(screen.getByRole("button", { name: "AI 검색 실행" }));

    expect(
      await screen.findByText("신뢰도 80% 이상 조건은 아직 목록 필터에 적용할 수 없어 제외했어요"),
    ).not.toBeNull();
  });

  it("등급 이하 범위(E로 안 끝남)는 적용하지 않고 안내하며 기존 필터를 유지한다", async () => {
    server.use(
      http.post("/api/defects/nl-search", () =>
        HttpResponse.json({
          success: true,
          data: {
            filters: { type: [], grade: ["A", "B"], status: [], confidenceMin: null },
            unsupported_terms: [],
            clarifying_question: null,
            interpretation_confidence: 0.9,
          },
        }),
      ),
    );
    // grade만 인식됐지만 미표현 범위라 적용 가능 조건 0건 → 기존 수동필터 유지(onChange 미호출), 안내만.
    const { onChange } = renderFilterBar({ page: 0, size: 20 });

    fireEvent.change(screen.getByLabelText("AI 자연어 검색"), {
      target: { value: "B등급 이하 하자 보여줘" },
    });
    fireEvent.click(screen.getByRole("button", { name: "AI 검색 실행" }));

    expect(
      await screen.findByText("등급 A, B 조건은 아직 목록 필터에 정확히 적용할 수 없어 제외했어요"),
    ).not.toBeNull();
    expect(onChange).not.toHaveBeenCalled();
  });

  it("[P2-1] 단일 non-E 등급(A만)은 `>=`로 오노출되므로 적용하지 않고 안내한다", async () => {
    server.use(
      http.post("/api/defects/nl-search", () =>
        HttpResponse.json({
          success: true,
          data: {
            filters: { type: [], grade: ["A"], status: [], confidenceMin: null },
            unsupported_terms: [],
            clarifying_question: null,
            interpretation_confidence: 0.9,
          },
        }),
      ),
    );
    const { onChange } = renderFilterBar({ page: 0, size: 20 });

    fireEvent.change(screen.getByLabelText("AI 자연어 검색"), {
      target: { value: "A등급 하자만 보여줘" },
    });
    fireEvent.click(screen.getByRole("button", { name: "AI 검색 실행" }));

    expect(
      await screen.findByText("등급 A 조건은 아직 목록 필터에 정확히 적용할 수 없어 제외했어요"),
    ).not.toBeNull();
    expect(onChange).not.toHaveBeenCalled();
  });

  it("[P2-3] 내림차순(E, D)으로 와도 정렬 후 min으로 적용한다", async () => {
    server.use(
      http.post("/api/defects/nl-search", () =>
        HttpResponse.json({
          success: true,
          data: {
            filters: { type: [], grade: ["E", "D"], status: [], confidenceMin: null },
            unsupported_terms: [],
            clarifying_question: null,
            interpretation_confidence: 0.9,
          },
        }),
      ),
    );
    const { onChange } = renderFilterBar({ page: 0, size: 20 });

    fireEvent.change(screen.getByLabelText("AI 자연어 검색"), {
      target: { value: "중대·경고 하자 보여줘" },
    });
    fireEvent.click(screen.getByRole("button", { name: "AI 검색 실행" }));

    await waitFor(() =>
      expect(onChange).toHaveBeenCalledWith({
        page: 0,
        size: 20,
        type: undefined,
        grade: "D",
        status: undefined,
      }),
    );
    expect(screen.queryByText(/정확히 적용할 수 없어/)).toBeNull();
  });

  it("[P2-2] 적용 가능한 조건이 0건이면 기존 수동필터를 유지하고 안내만 한다", async () => {
    server.use(
      http.post("/api/defects/nl-search", () =>
        HttpResponse.json({
          success: true,
          data: {
            filters: { type: [], grade: [], status: [], confidenceMin: null },
            unsupported_terms: ["지하주차장"],
            clarifying_question: null,
            interpretation_confidence: 0.9,
          },
        }),
      ),
    );
    // 사전 수동필터(type=CRACK)가 있는 상태에서 전 필드 빈 배열 응답이 와도 조용히 날리지 않는다.
    const { onChange } = renderFilterBar({ type: "CRACK", page: 0, size: 20 });

    fireEvent.change(screen.getByLabelText("AI 자연어 검색"), {
      target: { value: "지하주차장 하자 보여줘" },
    });
    fireEvent.click(screen.getByRole("button", { name: "AI 검색 실행" }));

    expect(
      await screen.findByText("다음 조건은 아직 지원하지 않아 제외했어요: 지하주차장"),
    ).not.toBeNull();
    expect(onChange).not.toHaveBeenCalled();
  });

  it("되묻는 질문이 오면 필터를 적용하지 않고 질문만 보여준다", async () => {
    const { onChange } = renderFilterBar({ page: 0, size: 20 });

    fireEvent.change(screen.getByLabelText("AI 자연어 검색"), {
      target: { value: "하자 좀 보여줘" },
    });
    fireEvent.click(screen.getByRole("button", { name: "AI 검색 실행" }));

    expect(
      await screen.findByText("어떤 유형·등급·상태의 하자를 찾으시나요?"),
    ).not.toBeNull();
    expect(onChange).not.toHaveBeenCalled();
  });

  it("AI_ADDON_REQUIRED 실패 시 업그레이드 안내를 보여주고 기존 필터를 유지한다", async () => {
    server.use(
      http.post("/api/defects/nl-search", () =>
        HttpResponse.json(
          { success: false, data: null, error: { code: "AI_ADDON_REQUIRED", message: "플랜 제한" } },
          { status: 403 },
        ),
      ),
    );
    const { onChange } = renderFilterBar({ page: 0, size: 20 });

    fireEvent.change(screen.getByLabelText("AI 자연어 검색"), {
      target: { value: "균열만 보여줘" },
    });
    fireEvent.click(screen.getByRole("button", { name: "AI 검색 실행" }));

    expect(
      await screen.findByText("AI 자연어 검색은 AI 부가 기능이 포함된 플랜에서만 사용할 수 있습니다."),
    ).not.toBeNull();
    expect(onChange).not.toHaveBeenCalled();
  });

  it("다중 값 중 첫 값만 적용될 때 제외된 값을 안내한다", async () => {
    server.use(
      http.post("/api/defects/nl-search", () =>
        HttpResponse.json({
          success: true,
          data: {
            filters: { type: ["CRACK", "SPALLING"], grade: [], status: [], confidenceMin: null },
            unsupported_terms: [],
            clarifying_question: null,
            interpretation_confidence: 0.9,
          },
        }),
      ),
    );
    renderFilterBar({ page: 0, size: 20 });

    fireEvent.change(screen.getByLabelText("AI 자연어 검색"), {
      target: { value: "균열이나 박리박락 보여줘" },
    });
    fireEvent.click(screen.getByRole("button", { name: "AI 검색 실행" }));

    expect(
      await screen.findByText("유형 박리·박락은(는) 아직 함께 적용할 수 없어 제외했어요"),
    ).not.toBeNull();
  });

  it("필터 초기화를 누르면 이전 AI 응답 배너도 함께 사라진다", async () => {
    renderFilterBar({ type: "CRACK", page: 0, size: 20 });

    fireEvent.change(screen.getByLabelText("AI 자연어 검색"), {
      target: { value: "하자 좀 보여줘" },
    });
    fireEvent.click(screen.getByRole("button", { name: "AI 검색 실행" }));
    await screen.findByText("어떤 유형·등급·상태의 하자를 찾으시나요?");

    fireEvent.click(screen.getByRole("button", { name: "필터 초기화" }));

    await waitFor(() =>
      expect(screen.queryByText("어떤 유형·등급·상태의 하자를 찾으시나요?")).toBeNull(),
    );
  });

  it("개별 필터 칩을 제거하면 이전 AI 응답 배너도 함께 사라진다", async () => {
    renderFilterBar({ type: "CRACK", page: 0, size: 20 });

    fireEvent.change(screen.getByLabelText("AI 자연어 검색"), {
      target: { value: "하자 좀 보여줘" },
    });
    fireEvent.click(screen.getByRole("button", { name: "AI 검색 실행" }));
    await screen.findByText("어떤 유형·등급·상태의 하자를 찾으시나요?");

    fireEvent.click(screen.getByRole("button", { name: "유형: 균열 필터 제거" }));

    await waitFor(() =>
      expect(screen.queryByText("어떤 유형·등급·상태의 하자를 찾으시나요?")).toBeNull(),
    );
  });
});
