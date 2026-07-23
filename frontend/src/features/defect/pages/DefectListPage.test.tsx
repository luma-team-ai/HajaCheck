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
import {
  afterAll,
  afterEach,
  beforeAll,
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from "vitest";
import { defectHandlers } from "../api/defectApi.handlers";
import { DefectListPage } from "./DefectListPage";

const mockExportDefectsToPdf = vi.fn().mockResolvedValue(undefined);
vi.mock("../utils/exportDefectsToPdf", () => ({
  exportDefectsToPdf: (...args: unknown[]) => mockExportDefectsToPdf(...args),
}));

const server = setupServer(...defectHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
beforeEach(() => {
  mockExportDefectsToPdf.mockClear();
});
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

// 목록→상세 이동(HAJA-17) 및 보고서 생성(목록→점검 회차 뷰어) 이동을 검증하기 위해 /defects/:id,
// /inspections/:id/viewer에 마커를 렌더링하는 스텁 라우트를 둔다(대상 페이지 전체를 렌더링할 필요
// 없이 navigate 대상만 확인하면 충분).
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
          <Route
            path="/inspections/:id/viewer"
            element={<div>점검 회차 뷰어 스텁</div>}
          />
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

  it("선택된 행이 없으면 보고서 생성·내보내기 버튼이 비활성화된다", async () => {
    renderPage();
    await screen.findByRole("table");

    const reportButton = screen.getByRole("button", {
      name: "보고서 생성",
    }) as HTMLButtonElement;
    const exportButton = screen.getByRole("button", {
      name: "내보내기",
    }) as HTMLButtonElement;
    expect(reportButton.disabled).toBe(true);
    expect(exportButton.disabled).toBe(true);
  });

  it("같은 점검 회차의 하자만 선택하면 보고서 생성 버튼이 활성화되고, 클릭 시 해당 점검 회차 뷰어로 이동한다", async () => {
    renderPage();
    const table = await screen.findByRole("table");

    // mockDefects: id 1, 2는 inspectionId 101을 공유한다.
    fireEvent.click(within(table).getByRole("checkbox", { name: "DEF-0001 선택" }));
    fireEvent.click(within(table).getByRole("checkbox", { name: "DEF-0002 선택" }));

    const reportButton = screen.getByRole("button", {
      name: "보고서 생성",
    }) as HTMLButtonElement;
    expect(reportButton.disabled).toBe(false);

    fireEvent.click(reportButton);

    expect(await screen.findByText("점검 회차 뷰어 스텁")).not.toBeNull();
  });

  it("서로 다른 점검 회차의 하자를 함께 선택하면 보고서 생성 버튼이 비활성화된다", async () => {
    renderPage();
    const table = await screen.findByRole("table");

    // mockDefects: id 1은 inspectionId 101, id 3은 inspectionId 202로 서로 다른 회차다.
    fireEvent.click(within(table).getByRole("checkbox", { name: "DEF-0001 선택" }));
    fireEvent.click(within(table).getByRole("checkbox", { name: "DEF-0003 선택" }));

    const reportButton = screen.getByRole("button", {
      name: "보고서 생성",
    }) as HTMLButtonElement;
    expect(reportButton.disabled).toBe(true);
    expect(reportButton.getAttribute("title")).toBe(
      "같은 점검 회차의 하자만 선택하세요",
    );
  });

  it("하자를 하나 이상 선택하면 내보내기 버튼이 활성화되고, 클릭 시 선택된 하자로 PDF 내보내기를 호출한다", async () => {
    renderPage();
    const table = await screen.findByRole("table");

    fireEvent.click(within(table).getByRole("checkbox", { name: "DEF-0001 선택" }));

    const exportButton = screen.getByRole("button", {
      name: "내보내기",
    }) as HTMLButtonElement;
    expect(exportButton.disabled).toBe(false);

    fireEvent.click(exportButton);

    await screen.findByRole("button", { name: "내보내기" });
    expect(mockExportDefectsToPdf).toHaveBeenCalledTimes(1);
    const [calledDefects] = mockExportDefectsToPdf.mock.calls[0];
    expect(calledDefects).toHaveLength(1);
    expect(calledDefects[0].id).toBe(1);
  });

  it("PDF 내보내기가 실패해도 버튼이 다시 클릭 가능한 상태로 복원된다", async () => {
    mockExportDefectsToPdf.mockRejectedValueOnce(new Error("font fetch failed"));
    const consoleErrorSpy = vi
      .spyOn(console, "error")
      .mockImplementation(() => {});

    renderPage();
    const table = await screen.findByRole("table");

    fireEvent.click(within(table).getByRole("checkbox", { name: "DEF-0001 선택" }));

    const exportButton = screen.getByRole("button", {
      name: "내보내기",
    }) as HTMLButtonElement;
    fireEvent.click(exportButton);

    await screen.findByRole("button", { name: "내보내기" });
    expect(exportButton.disabled).toBe(false);
    expect(consoleErrorSpy).toHaveBeenCalledWith(
      "하자 목록 PDF 내보내기 실패",
      expect.any(Error),
    );

    consoleErrorSpy.mockRestore();
  });

  it("보드 보기 탭을 클릭하면 목록 대신 조치 보드를 렌더링한다(HAJA-349/#630)", async () => {
    renderPage();
    await screen.findByRole("table");

    fireEvent.click(screen.getByRole("tab", { name: "보드 보기" }));

    expect(screen.queryByRole("table")).toBeNull();
    expect(await screen.findByLabelText("신규 컬럼")).not.toBeNull();
    expect(screen.getByLabelText("조치완료 컬럼")).not.toBeNull();
  });

  it("목록 보기로 돌아가면 다시 테이블과 페이지네이션을 렌더링한다", async () => {
    renderPage();
    await screen.findByRole("table");

    fireEvent.click(screen.getByRole("tab", { name: "보드 보기" }));
    await screen.findByLabelText("신규 컬럼");

    fireEvent.click(screen.getByRole("tab", { name: "목록 보기" }));

    expect(await screen.findByRole("table")).not.toBeNull();
  });

  it("탭 버튼과 탭 패널이 aria-controls/aria-labelledby로 연결된다(code-reviewer 지적)", async () => {
    renderPage();
    await screen.findByRole("table");

    const listTab = screen.getByRole("tab", { name: "목록 보기" });
    const [listPanel] = screen.getAllByRole("tabpanel");
    expect(listTab.getAttribute("aria-controls")).toBe(listPanel.id);
    expect(listPanel.getAttribute("aria-labelledby")).toBe(listTab.id);

    fireEvent.click(screen.getByRole("tab", { name: "보드 보기" }));
    await screen.findByLabelText("신규 컬럼");

    const boardTab = screen.getByRole("tab", { name: "보드 보기" });
    const [boardPanel] = screen.getAllByRole("tabpanel");
    expect(boardTab.getAttribute("aria-controls")).toBe(boardPanel.id);
    expect(boardPanel.getAttribute("aria-labelledby")).toBe(boardTab.id);
  });
});
