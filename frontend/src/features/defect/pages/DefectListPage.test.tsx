// @vitest-environment jsdom
// DefectListPage 통합 테스트 — HAJA-393/394(#725/#726)로 점검(Inspection) 단위 테이블로 재해석된 것을
// 검증한다. 2026-07-26 정정(#726 코멘트): "목록 보기/보드 보기" 2탭 구조는 설계 오류로 확정되어
// 제거되었다 — 이 페이지는 다시 점검 단위 목록 단일 플로우만 렌더링한다("보드 보기" 탭 관련 테스트는
// 삭제, DefectActionBoard.test.tsx가 컴포넌트 단위로 별도 커버).
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
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
import { api } from "../../../shared/api/axios";
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

// 목록→점검 상세(카드형) 이동을 검증하기 위해
// /inspections/:id/defects에 마커를 렌더링하는 스텁 라우트를 둔다.
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

    // mockInspections id=101: id 1,2,4 → 3건, 등급 B/C/D 각 1건.
    expect(within(table).getByText("3건")).not.toBeNull();
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

  it("보고서 생성 버튼과 점검 선택 체크박스를 표시하지 않고, 필터 결과가 있으면 내보내기를 활성화한다", async () => {
    renderPage();
    const table = await screen.findByRole("table");

    const exportButton = screen.getByRole("button", {
      name: "내보내기",
    }) as HTMLButtonElement;
    expect(screen.queryByRole("button", { name: "보고서 생성" })).toBeNull();
    expect(within(table).queryByRole("checkbox")).toBeNull();
    expect(exportButton.disabled).toBe(false);
  });

  it("현재 페이지와 무관하게 필터 결과의 모든 점검에 속한 하자를 모아 PDF로 내보낸다", async () => {
    renderPage();
    await screen.findByRole("table");

    const exportButton = screen.getByRole("button", {
      name: "내보내기",
    }) as HTMLButtonElement;
    expect(exportButton.disabled).toBe(false);

    fireEvent.click(exportButton);

    await waitFor(() => expect(mockExportDefectsToPdf).toHaveBeenCalledTimes(1));
    const [calledDefects] = mockExportDefectsToPdf.mock.calls[0];
    // mockInspections 전체(101/202/301) 중 301은 하자 0건 → id 1, 2, 3, 4가 모인다.
    expect(calledDefects).toHaveLength(4);
    expect(calledDefects.map((defect: { id: number }) => defect.id).sort()).toEqual([1, 2, 3, 4]);
  });

  it("PDF 내보내기가 실패해도 버튼이 다시 클릭 가능한 상태로 복원된다", async () => {
    mockExportDefectsToPdf.mockRejectedValueOnce(new Error("font fetch failed"));
    const consoleErrorSpy = vi
      .spyOn(console, "error")
      .mockImplementation(() => {});

    renderPage();
    await screen.findByRole("table");

    const exportButton = screen.getByRole("button", {
      name: "내보내기",
    }) as HTMLButtonElement;
    fireEvent.click(exportButton);

    await waitFor(() => {
      expect(exportButton.disabled).toBe(false);
      expect(consoleErrorSpy).toHaveBeenCalledWith(
        "점검 하자 목록 PDF 내보내기 실패",
        expect.any(Error),
      );
    });
    expect((await screen.findByRole("alert")).textContent).toBe(
      "내보내기에 실패했습니다. 잠시 후 다시 시도해 주세요.",
    );

    consoleErrorSpy.mockRestore();
  });

  it("자연어 검색 결과를 실제 점검 목록 요청에 적용하고 날짜·회차 칩을 표시한다", async () => {
    renderPage();
    await screen.findByRole("table");

    fireEvent.change(screen.getByLabelText("AI 자연어 검색"), {
      target: { value: "지난 두 달간의 1회차 점검 알려줘" },
    });
    fireEvent.click(screen.getByRole("button", { name: "AI 검색 실행" }));

    expect(
      await screen.findByRole("button", {
        name: "점검일: 2026-05-28 ~ 2026-07-28 필터 제거",
      }),
    ).not.toBeNull();
    expect(
      screen.getByRole("button", { name: "점검회차: 1회차 필터 제거" }),
    ).not.toBeNull();

    await waitFor(() => {
      const table = screen.getByRole("table");
      expect(within(table).getByText("한강대교 북단")).not.toBeNull();
      expect(within(table).queryByText("강남 오피스타워 A동")).toBeNull();
    });
  });

  it("AI 필터를 적용한 뒤 내보내면 해당 필터 결과의 하자만 PDF에 포함한다", async () => {
    renderPage();
    await screen.findByRole("table");

    fireEvent.change(screen.getByLabelText("AI 자연어 검색"), {
      target: { value: "지난 두 달간의 1회차 점검 알려줘" },
    });
    fireEvent.click(screen.getByRole("button", { name: "AI 검색 실행" }));

    await waitFor(() => {
      const table = screen.getByRole("table");
      expect(within(table).getByText("한강대교 북단")).not.toBeNull();
      expect(within(table).queryByText("강남 오피스타워 A동")).toBeNull();
    });

    fireEvent.click(screen.getByRole("button", { name: "내보내기" }));

    await waitFor(() => expect(mockExportDefectsToPdf).toHaveBeenCalledTimes(1));
    const [calledDefects] = mockExportDefectsToPdf.mock.calls[0];
    expect(calledDefects.map((defect: { id: number }) => defect.id)).toEqual([3]);
  });
});

// 전역 MSW 핸들러 등록 순서 회귀 방지 — mocks/handlers.ts는 inspectionHandlers를 defectHandlers보다
// 먼저 등록한다. inspectionApi.handlers.ts의 GET /api/inspections(시설물 단건 중복확인 전용, facilityId만
// 전송)가 page 파라미터 유무로 스스로 분기하지 않으면, 하자 목록(useInspections)의 page/size 포함
// 요청까지 먼저 가로채 항상 빈 목록을 반환해버려 자연어 검색을 포함한 모든 필터가 무동작으로
// 보인다(이번에 실제로 재현된 버그) — 위 describe들은 격리된 setupServer(...defectHandlers)만 써서
// 이 충돌을 잡지 못했으므로(InspectionDefectsPage.test.tsx의 동일 패턴 참고), 반드시 전역 handlers
// 배열로 별도 서버를 띄워 검증한다.
describe("DefectListPage — 전역 MSW 핸들러 등록 순서 회귀 테스트", () => {
  it(
    "page 파라미터를 포함한 점검 목록 조회는 inspectionHandlers의 중복확인 목이 아니라 defectHandlers의 실 데이터를 반환한다",
    async () => {
      const { allMockHandlers } = await import("../../../mocks/handlers");
      const globalServer = setupServer(...allMockHandlers);
      globalServer.listen({ onUnhandledRequest: "error" });

      try {
        const response = await api.get("/inspections", {
          params: { page: 0, size: 10 },
        });
        // 회귀 시(inspectionHandlers가 무조건 가로챔): totalElements === 0, content === [].
        // mockInspections(101/202/301) 기준 실제로는 3건이 반환돼야 한다.
        expect(response.data.totalElements).toBeGreaterThan(0);
        expect(response.data.content.length).toBeGreaterThan(0);
      } finally {
        globalServer.close();
      }
    },
    15_000,
  );
});
