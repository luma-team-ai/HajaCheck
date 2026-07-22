// @vitest-environment jsdom
// DefectListPage 통합 테스트 — 실제 useDefects 훅 + MSW defectHandlers를 통해 목록 렌더링과
// 초기 렌더링을 검증한다(HAJA-30, FacilityListPage.test.tsx와 동일 패턴).
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  within,
} from "@testing-library/react";
import { setupServer } from "msw/node";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { defectHandlers } from "../api/defectApi.handlers";
import { DefectListPage } from "./DefectListPage";

const server = setupServer(...defectHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

// 목록→상세 이동(HAJA-17)을 검증하기 위해 /defects/:id에 마커를 렌더링하는 스텁 라우트를 둔다
// (DefectDetailPage 전체를 렌더링할 필요 없이 navigate 대상만 확인하면 충분).
function renderPage(): void {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });

  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={["/defects/list"]}>
        <Routes>
          <Route path="/defects/list" element={<DefectListPage />} />
          <Route path="/defects/:id" element={<div>하자 상세 스텁</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("DefectListPage (통합 테스트)", () => {
  it("초기 목록: MSW 목 데이터를 불러와 테이블에 렌더링한다", async () => {
    renderPage();

    const table = await screen.findByRole("table");
    expect(within(table).getByText("철근 노출")).not.toBeNull();
    expect(within(table).getByText("균열")).not.toBeNull();
    expect(within(table).getByText("박리·박락")).not.toBeNull();
  });

  it("LLM 검색 조건이 없으면 적용된 필터 영역을 표시하지 않는다", async () => {
    renderPage();
    await screen.findByRole("table");

    expect(screen.queryByText("적용된 필터:")).toBeNull();
    expect(screen.queryByRole("button", { name: "필터 초기화" })).toBeNull();
  });

  it("상세보기 링크가 각 행에 렌더링된다", async () => {
    renderPage();
    const table = await screen.findByRole("table");

    const detailLinks = within(table).getAllByRole("link", {
      name: "상세보기",
    });
    expect(detailLinks.length).toBeGreaterThan(0);
    expect(detailLinks[0].getAttribute("href")).toMatch(/^\/defects\/\d+$/);
  });

  it("행을 클릭하면 해당 하자의 상세 페이지로 이동한다", async () => {
    renderPage();
    const table = await screen.findByRole("table");
    const rows = within(table).getAllByRole("row");

    // rows[0]은 헤더 행 — 첫 데이터 행을 클릭한다.
    fireEvent.click(rows[1]);

    expect(await screen.findByText("하자 상세 스텁")).not.toBeNull();
  });

  it("행 선택을 클릭해도 상세 페이지로 이동하지 않는다", async () => {
    renderPage();
    const table = await screen.findByRole("table");
    const checkbox = within(table).getByRole("checkbox", {
      name: "DEF-0001 선택",
    });

    fireEvent.click(checkbox);

    expect(screen.queryByText("하자 상세 스텁")).toBeNull();
  });

  it("헤더에서 현재 페이지의 하자를 전체 선택하고 해제한다", async () => {
    renderPage();
    const table = await screen.findByRole("table");
    const selectAll = within(table).getByRole("checkbox", {
      name: "현재 페이지 하자 전체 선택",
    });
    const rowSelections = within(table)
      .getAllByRole("checkbox")
      .filter((checkbox) => checkbox !== selectAll) as HTMLInputElement[];

    fireEvent.click(selectAll);
    expect(rowSelections.every((checkbox) => checkbox.checked)).toBe(true);

    fireEvent.click(selectAll);
    expect(rowSelections.every((checkbox) => !checkbox.checked)).toBe(true);
  });

  it("일부 행만 선택하면 헤더 선택에 중간 상태를 표시한다", async () => {
    renderPage();
    const table = await screen.findByRole("table");
    const selectAll = within(table).getByRole("checkbox", {
      name: "현재 페이지 하자 전체 선택",
    }) as HTMLInputElement;
    const rowSelection = within(table).getByRole("checkbox", {
      name: "DEF-0001 선택",
    });

    fireEvent.click(rowSelection);

    expect(selectAll.checked).toBe(false);
    expect(selectAll.indeterminate).toBe(true);
  });
});
