// @vitest-environment jsdom
// DefectListPage 통합 테스트 — HAJA-393/394(#725/#726)로 "목록 보기" 탭이 점검(Inspection) 단위
// 테이블로 재해석된 것을 검증한다. "보드 보기" 탭은 여전히 하자 단건 기준(HAJA-349/#630, 변경 없음)
// 이라 관련 테스트는 원본(DefectListPage.test.tsx, HAJA-30)과 동일하게 유지한다.
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

// 목록→점검 상세(카드형) 이동, 보고서 생성(목록→점검 회차 뷰어) 이동을 검증하기 위해
// /inspections/:id/defects, /inspections/:id/viewer에 마커를 렌더링하는 스텁 라우트를 둔다.
function renderPage(): void {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });

  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={["/defects/list"]}>
        <Routes>
          <Route path="/defects/list" element={<DefectListPage />} />
          <Route
            path="/inspections/:id/defects"
            element={<div>점검 상세 스텁</div>}
          />
          <Route
            path="/inspections/:id/viewer"
            element={<div>점검 회차 뷰어 스텁</div>}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("DefectListPage — 목록 보기 탭(점검 단위, HAJA-393/394)", () => {
  it("초기 목록: MSW 목 데이터를 불러와 점검 단위 테이블에 렌더링한다", async () => {
    renderPage();

    const table = await screen.findByRole("table");
    expect(within(table).getByText("강남 오피스타워 A동")).not.toBeNull();
    expect(within(table).getByText("한강대교 북단")).not.toBeNull();
    expect(within(table).getByText("판교 테크노밸리 B동")).not.toBeNull();
  });

  it("점검별 하자 건수·등급분포를 mockDefects 기준으로 집계해 표시한다", async () => {
    renderPage();
    const table = await screen.findByRole("table");

    // mockInspections id=101: mockDefects의 inspectionId=101(id 1,2) → 2건, 등급 D/C 각 1건.
    expect(within(table).getByText("2건")).not.toBeNull();
    // id=202: inspectionId=202(id 3) → 1건, grade=null이라 등급분포는 '-'.
    expect(within(table).getByText("1건")).not.toBeNull();
    // id=301: 하자 없음(빈 상태) → 0건.
    expect(within(table).getByText("0건")).not.toBeNull();
  });

  it("적용된 필터가 없으면 필터 칩 영역을 표시하지 않는다", async () => {
    renderPage();
    await screen.findByRole("table");

    expect(screen.queryByText("적용된 필터:")).toBeNull();
  });

  it("점검 상세보기 링크가 각 행에 렌더링된다", async () => {
    renderPage();
    const table = await screen.findByRole("table");

    const detailLinks = within(table).getAllByRole("link", {
      name: "점검 상세보기",
    });
    expect(detailLinks.length).toBeGreaterThan(0);
    expect(detailLinks[0].getAttribute("href")).toMatch(
      /^\/inspections\/\d+\/defects$/,
    );
  });

  it("행을 클릭하면 해당 점검의 상세(카드형) 페이지로 이동한다", async () => {
    renderPage();
    const table = await screen.findByRole("table");
    const rows = within(table).getAllByRole("row");

    // rows[0]은 헤더 행 — 첫 데이터 행을 클릭한다.
    fireEvent.click(rows[1]);

    expect(await screen.findByText("점검 상세 스텁")).not.toBeNull();
  });

  it("행 선택을 클릭해도 상세 페이지로 이동하지 않는다", async () => {
    renderPage();
    const table = await screen.findByRole("table");
    const checkbox = within(table).getByRole("checkbox", {
      name: "INS-0101 선택",
    });

    fireEvent.click(checkbox);

    expect(screen.queryByText("점검 상세 스텁")).toBeNull();
  });

  it("헤더에서 현재 페이지의 점검을 전체 선택하고 해제한다", async () => {
    renderPage();
    const table = await screen.findByRole("table");
    const selectAll = within(table).getByRole("checkbox", {
      name: "현재 페이지 점검 전체 선택",
    });
    const rowSelections = within(table)
      .getAllByRole("checkbox")
      .filter((checkbox) => checkbox !== selectAll) as HTMLInputElement[];

    fireEvent.click(selectAll);
    expect(rowSelections.every((checkbox) => checkbox.checked)).toBe(true);

    fireEvent.click(selectAll);
    expect(rowSelections.every((checkbox) => !checkbox.checked)).toBe(true);
  });

  it("선택된 점검이 없으면 보고서 생성·내보내기 버튼이 비활성화된다", async () => {
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

  it("점검 1건을 선택하면 보고서 생성 버튼이 활성화되고, 클릭 시 해당 점검 회차 뷰어로 이동한다", async () => {
    renderPage();
    const table = await screen.findByRole("table");

    fireEvent.click(within(table).getByRole("checkbox", { name: "INS-0101 선택" }));

    const reportButton = screen.getByRole("button", {
      name: "보고서 생성",
    }) as HTMLButtonElement;
    expect(reportButton.disabled).toBe(false);

    fireEvent.click(reportButton);

    expect(await screen.findByText("점검 회차 뷰어 스텁")).not.toBeNull();
  });

  it("점검을 2건 이상 선택하면 보고서 생성 버튼이 비활성화된다", async () => {
    renderPage();
    const table = await screen.findByRole("table");

    fireEvent.click(within(table).getByRole("checkbox", { name: "INS-0101 선택" }));
    fireEvent.click(within(table).getByRole("checkbox", { name: "INS-0202 선택" }));

    const reportButton = screen.getByRole("button", {
      name: "보고서 생성",
    }) as HTMLButtonElement;
    expect(reportButton.disabled).toBe(true);
  });

  it("점검을 하나 이상 선택하면 내보내기 버튼이 활성화되고, 선택된 점검에 속한 하자를 모아 PDF로 내보낸다", async () => {
    renderPage();
    const table = await screen.findByRole("table");

    fireEvent.click(within(table).getByRole("checkbox", { name: "INS-0101 선택" }));

    const exportButton = screen.getByRole("button", {
      name: "내보내기",
    }) as HTMLButtonElement;
    expect(exportButton.disabled).toBe(false);

    fireEvent.click(exportButton);

    await screen.findByRole("button", { name: "내보내기" });
    expect(mockExportDefectsToPdf).toHaveBeenCalledTimes(1);
    const [calledDefects] = mockExportDefectsToPdf.mock.calls[0];
    // mockInspections id=101 → mockDefects(inspectionId=101)의 id 1, 2가 그대로 모여야 한다.
    expect(calledDefects).toHaveLength(2);
    expect(calledDefects.map((defect: { id: number }) => defect.id).sort()).toEqual([1, 2]);
  });

  it("PDF 내보내기가 실패해도 버튼이 다시 클릭 가능한 상태로 복원된다", async () => {
    mockExportDefectsToPdf.mockRejectedValueOnce(new Error("font fetch failed"));
    const consoleErrorSpy = vi
      .spyOn(console, "error")
      .mockImplementation(() => {});

    renderPage();
    const table = await screen.findByRole("table");

    fireEvent.click(within(table).getByRole("checkbox", { name: "INS-0101 선택" }));

    const exportButton = screen.getByRole("button", {
      name: "내보내기",
    }) as HTMLButtonElement;
    fireEvent.click(exportButton);

    await screen.findByRole("button", { name: "내보내기" });
    expect(exportButton.disabled).toBe(false);
    expect(consoleErrorSpy).toHaveBeenCalledWith(
      "점검 하자 목록 PDF 내보내기 실패",
      expect.any(Error),
    );

    consoleErrorSpy.mockRestore();
  });
});

describe("DefectListPage — 보드 보기 탭(하자 단건, HAJA-349/#630, 변경 없음)", () => {
  it("보드 보기 탭을 클릭하면 목록 대신 조치 보드를 렌더링한다", async () => {
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
