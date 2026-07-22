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
});
