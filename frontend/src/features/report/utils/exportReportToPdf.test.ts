// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ReportContent } from "../types";
import { mockReportDetailResponse } from "../mocks/reportDetail.mock";
import { buildReportPdfFileName, exportReportToPdf } from "./exportReportToPdf";

const mockOutput = vi.fn().mockReturnValue(new Blob(["fake-pdf-bytes"]));
const mockAddFileToVFS = vi.fn();
const mockAddFont = vi.fn();
const mockSetFont = vi.fn();
const mockSetFontSize = vi.fn();
const mockText = vi.fn();
const mockAddPage = vi.fn();
const mockSplitTextToSize = vi.fn((text: string) => [text]);
const mockAddImage = vi.fn();
const mockSetPage = vi.fn();
const mockAutoTable = vi.fn((doc: MockJsPDF, _options: unknown) => {
  void _options;
  doc.lastAutoTable = { finalY: 120 };
});

class MockJsPDF {
  addFileToVFS = mockAddFileToVFS;
  addFont = mockAddFont;
  setFont = mockSetFont;
  setFontSize = mockSetFontSize;
  text = mockText;
  addPage = mockAddPage;
  splitTextToSize = mockSplitTextToSize;
  addImage = mockAddImage;
  lastAutoTable = { finalY: 0 };
  setLineHeightFactor = vi.fn();
  setDrawColor = vi.fn();
  setLineWidth = vi.fn();
  setTextColor = vi.fn();
  getTextWidth = vi.fn(() => 60);
  rect = vi.fn();
  line = vi.fn();
  getNumberOfPages = vi.fn(() => 5);
  setPage = mockSetPage;
  output = mockOutput;
}

vi.mock("jspdf", () => ({
  default: MockJsPDF,
}));

vi.mock("jspdf-autotable", () => ({
  default: mockAutoTable,
}));

vi.mock("../../../assets/fonts/NotoSansKR-Regular.subset.ttf?url", () => ({
  default: "https://example.test/NotoSansKR-Regular.subset.ttf",
}));

vi.mock("../../../assets/fonts/NotoSansKR-Bold.subset.ttf?url", () => ({
  default: "https://example.test/NotoSansKR-Bold.subset.ttf",
}));

function makeContent(overrides: Partial<ReportContent> = {}): ReportContent {
  return {
    overview: {
      purpose: "정기 점검",
      facility_summary: "테스트 시설물",
      scope: "전체",
    },
    summary: {
      overall_opinion: "양호",
      total_count: 1,
      count_by_grade: { A: 0, B: 0, C: 1, D: 0, E: 0 },
      key_findings: ["균열 발견"],
    },
    detail: {
      items: [
        {
          defect_type: "균열",
          location: "1층 벽체",
          severity_grade: "C",
          description: "설명",
          cause: "원인",
        },
      ],
    },
    recommendation: {
      items: [
        {
          target: "1층 벽체",
          method: "보수",
          priority: "중",
          legal_basis: "관련 근거 없음",
          legal_basis_verified: false,
        },
      ],
      monitoring_points: ["정기 재점검"],
    },
    ...overrides,
  };
}

/** autoTable 호출 중 조건에 맞는 첫 옵션을 찾는다. */
function findTableOptions(
  predicate: (options: Record<string, unknown>) => boolean,
): Record<string, unknown> | undefined {
  return mockAutoTable.mock.calls
    .map(([, options]) => options as Record<string, unknown>)
    .find(predicate);
}

describe("exportReportToPdf", () => {
  beforeEach(() => {
    mockOutput.mockClear();
    mockAddFileToVFS.mockClear();
    mockAddFont.mockClear();
    mockSetFont.mockClear();
    mockSetFontSize.mockClear();
    mockText.mockClear();
    mockAddPage.mockClear();
    mockSplitTextToSize.mockClear();
    mockAddImage.mockClear();
    mockSetPage.mockClear();
    mockAutoTable.mockClear();

    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        blob: () => Promise.resolve(new Blob(["fake-font-bytes"])),
      }),
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("한글 폰트를 임베딩하고 content 섹션들을 렌더링한 뒤 Blob을 반환한다", async () => {
    const blob = await exportReportToPdf(makeContent());

    expect(mockAddFont).toHaveBeenCalledWith(
      "NotoSansKR-Regular.ttf",
      "NotoSansKR",
      "normal",
    );
    expect(mockAddFont).toHaveBeenCalledWith(
      "NotoSansKR-Bold.ttf",
      "NotoSansKR",
      "bold",
    );
    expect(mockAutoTable).toHaveBeenCalled();
    expect(mockOutput).toHaveBeenCalledWith("blob");
    expect(blob).toBeInstanceOf(Blob);
  });

  it("실백엔드 상세 응답 fixture의 content로 PDF Blob을 생성한다", async () => {
    const blob = await exportReportToPdf(
      mockReportDetailResponse.content as ReportContent,
    );

    expect(
      findTableOptions((options) => Array.isArray(options.head)),
    ).toBeDefined();
    expect(mockOutput).toHaveBeenCalledWith("blob");
    expect(blob).toBeInstanceOf(Blob);
  });

  it("기본 순서(고정 4섹션)일 때 번호가 매겨진 절 제목을 순서대로 렌더링한다", async () => {
    await exportReportToPdf(makeContent(), {
      issuedAt: new Date("2026-07-26T00:00:00"),
    });

    const renderedText = mockText.mock.calls.map(([text]) => text).flat();
    expect(renderedText).toContain("1. 기본현황");
    expect(renderedText).toContain("가. 일반현황");
    expect(renderedText).toContain("2. 결과 요약");
    expect(renderedText).toContain("3. 진단 외관조사결과 기본사항");
    expect(renderedText).toContain("4. 보수ㆍ보강(안)");
    // 지원되지 않는 서명·참여자 필드는 만들지 않는다(수동 섹션을 추가하지 않은 기본 상태).
    expect(renderedText).not.toContain("제  출  문");
    expect(renderedText).not.toContain("작성자");
    expect(renderedText).not.toContain("(서명)");
    expect(renderedText).not.toContain("입회자");
  });

  it("여러 섹션이 한 페이지에 들어갈 만큼 남으면 새 페이지로 넘기지 않는다(원본처럼 소절을 채움)", async () => {
    await exportReportToPdf(makeContent());

    // mock autoTable은 매번 finalY=120을 반환 — 4섹션 모두 여유 공간(BOTTOM_LIMIT=274) 안에
    // 들어가므로 addPage가 전혀 필요 없다. 이전 버전(섹션마다 무조건 새 페이지)의 회귀 방지용.
    expect(mockAddPage).not.toHaveBeenCalled();
  });

  it("제출문·참여기술진 명단을 sectionOrder에 지정한 위치에 렌더링한다(수동 섹션)", async () => {
    const content = makeContent({
      manualSections: [
        {
          id: "manual-submission-1",
          type: "submission",
          title: "제출문",
          data: {
            recipient: "서울특별시장 귀하",
            contractDate: "2026년 04월 21일",
            companyName: "(재)한국안전연구원",
            companyAddress: "서울시 강남구",
            representativeName: "홍길동",
          },
        },
        {
          id: "manual-participants-1",
          type: "participants",
          title: "참여기술진 명단",
          data: {
            entries: [
              {
                role: "사업책임기술인",
                name: "김철수",
                qualification: "토목기사",
                period: "2026.01~06",
              },
            ],
          },
        },
      ],
      sectionOrder: [
        "manual-submission-1",
        "overview",
        "summary",
        "detail",
        "recommendation",
        "manual-participants-1",
      ],
    });

    await exportReportToPdf(content);

    const renderedText = mockText.mock.calls.map(([text]) => text).flat();
    expect(renderedText).toContain("제  출  문");
    expect(renderedText).toContain("서울특별시장 귀하");
    expect(renderedText).toContain("6. 참여기술진 명단");

    const participantsOptions = findTableOptions(
      (options) =>
        Array.isArray(options.head) &&
        (options.head as string[][])[0]?.includes("자격 및 주요경력"),
    );
    expect(participantsOptions?.body).toEqual([
      ["사업책임기술인", "김철수", "토목기사", "2026.01~06"],
    ]);

    // 제출문은 formal 커버 페이지라 예외적으로 다음 섹션(기본현황)을 강제로 새 페이지에
    // 앉힌다 — 그 전환 1회만 addPage가 발생하고, 이후 4개 고정 섹션 + 참여기술진 명단은
    // (mock finalY 고정값 기준) 한 페이지에 이어 쓴다.
    expect(mockAddPage).toHaveBeenCalledTimes(1);
  });

  it("안전성평가 결과 같은 generic 수동 섹션도 sectionOrder 위치에 관공서 표로 렌더링한다", async () => {
    const content = makeContent({
      manualSections: [
        {
          id: "manual-safety-1",
          type: "safety-assessment",
          title: "안전성평가 결과",
          data: { body: "구조 안전성 검토 결과를 입력합니다." },
        },
      ],
      sectionOrder: [
        "overview",
        "manual-safety-1",
        "summary",
        "detail",
        "recommendation",
      ],
    });

    await exportReportToPdf(content);

    const renderedText = mockText.mock.calls.map(([text]) => text).flat();
    expect(renderedText).toContain("2. 안전성평가 결과");
    const genericOptions = findTableOptions((options) =>
      JSON.stringify(options.body).includes(
        "구조 안전성 검토 결과를 입력합니다.",
      ),
    );
    expect(genericOptions?.body).toEqual([
      ["구조 안전성 검토 결과를 입력합니다."],
    ]);
  });

  it("부위별 사진도 다른 섹션과 동등하게 sectionOrder로 자유롭게 재배치된다", async () => {
    vi.mocked(fetch).mockImplementation((input) => {
      if (String(input) === "/api/media/1/thumbnail") {
        return Promise.resolve({
          ok: true,
          blob: () =>
            Promise.resolve(new Blob(["jpeg-bytes"], { type: "image/jpeg" })),
        } as Response);
      }
      return Promise.resolve({
        ok: true,
        blob: () => Promise.resolve(new Blob(["font-bytes"])),
      } as Response);
    });

    const content = makeContent({
      sectionOrder: [
        "photos",
        "overview",
        "summary",
        "detail",
        "recommendation",
      ],
    });

    await exportReportToPdf(content, {
      defectImages: [
        { defectType: "균열", imageUrl: "/api/media/1/thumbnail" },
      ],
    });

    const renderedText = mockText.mock.calls.map(([text]) => text).flat();
    // 맨 앞으로 옮겼으니 1번, 기본현황은 그 다음(2번)이 된다 — 고정 마지막 자리가 아니다.
    expect(renderedText).toContain("1. 부위별 사진");
    expect(renderedText).toContain("2. 기본현황");
  });

  it("사진이 0장이면 sectionOrder에 있어도 부위별 사진 섹션과 번호를 만들지 않는다", async () => {
    const content = makeContent({
      sectionOrder: [
        "photos",
        "overview",
        "summary",
        "detail",
        "recommendation",
      ],
    });

    await exportReportToPdf(content, { defectImages: [] });

    const renderedText = mockText.mock.calls.map(([text]) => text).flat();
    expect(
      renderedText.some(
        (text) => typeof text === "string" && text.includes("부위별 사진"),
      ),
    ).toBe(false);
    // 사진 섹션이 통째로 빠지므로 기본현황이 1번을 그대로 유지한다(번호에 구멍이 생기지 않음).
    expect(renderedText).toContain("1. 기본현황");
  });

  it("삭제된 수동 섹션의 잔여 sectionOrder id는 무시한다", async () => {
    const content = makeContent({
      manualSections: [],
      sectionOrder: [
        "manual-submission-stale",
        "overview",
        "summary",
        "detail",
        "recommendation",
      ],
    });

    await exportReportToPdf(content);

    const renderedText = mockText.mock.calls.map(([text]) => text).flat();
    expect(renderedText).not.toContain("제  출  문");
    expect(renderedText).toContain("1. 기본현황");
    // 제출문이 사라졌으니 강제 페이지 전환도 없다 — 4섹션이 한 페이지에 이어진다.
    expect(mockAddPage).not.toHaveBeenCalled();
  });

  it("흑백 인쇄 서식이므로 글자·괘선은 검정, 표 헤더 배경은 회색만 쓴다", async () => {
    await exportReportToPdf(makeContent());

    const options = findTableOptions((candidate) => Boolean(candidate.styles));
    const styles = options?.styles as {
      textColor: number[];
      lineColor: number[];
    };
    const headStyles = options?.headStyles as {
      fillColor: number[];
      textColor: number[];
    };
    expect(styles.textColor).toEqual([0, 0, 0]);
    expect(styles.lineColor).toEqual([0, 0, 0]);
    expect(headStyles.textColor).toEqual([0, 0, 0]);
    expect(headStyles.fillColor).toEqual([204, 204, 204]);
  });

  it("표 외곽 테두리를 내부 괘선보다 굵게 그린다(관공서 표 대비)", async () => {
    await exportReportToPdf(makeContent());

    const options = findTableOptions((candidate) => Boolean(candidate.styles));
    const inner = (options?.styles as { lineWidth: number }).lineWidth;
    const outer = options?.tableLineWidth as number;
    expect(outer).toBeGreaterThan(inner);
    expect(outer / inner).toBeCloseTo(3, 5);
  });

  it("머리말·꼬리말·페이지번호를 넣지 않는다(원본 서식에 없음)", async () => {
    await exportReportToPdf(makeContent());

    expect(mockSetPage).not.toHaveBeenCalled();
    const renderedText = mockText.mock.calls.map(([text]) => text).flat();
    expect(
      renderedText.some(
        (text) => typeof text === "string" && /^\d+\s*\/\s*\d+$/.test(text),
      ),
    ).toBe(false);
  });

  it("부재별 상태평가 등급은 소문자로 적는다(대문자는 시설물 종합 안전등급 전용)", async () => {
    await exportReportToPdf(makeContent());

    const options = findTableOptions(
      (candidate) =>
        Array.isArray(candidate.head) &&
        (candidate.head as string[][])[0]?.includes("결함발생 부재"),
    );
    expect(options?.body).toEqual([
      ["1", "1층 벽체", "c", "균열", "설명", "원인"],
    ]);
  });

  it("등급별 건수에서 최악 등급을 상태평가 결과로 표기한다", async () => {
    await exportReportToPdf(
      makeContent({
        summary: {
          overall_opinion: "주의",
          total_count: 3,
          count_by_grade: { A: 1, B: 0, C: 1, D: 1, E: 0 },
          key_findings: [],
        },
      }),
    );

    const options = findTableOptions(
      (candidate) =>
        Array.isArray(candidate.body) &&
        String((candidate.body as string[][])[0]?.[1] ?? "").startsWith(
          "상태평가 결과 :",
        ),
    );
    expect((options?.body as string[][])[0][1]).toBe("상태평가 결과 : d");
  });

  it("점검 축소본을 부위별 사진 표 안에 안전하게 배치한다(자동 페이지분할되는 autoTable 셀)", async () => {
    vi.mocked(fetch).mockImplementation((input) => {
      if (String(input) === "/api/media/1/thumbnail") {
        return Promise.resolve({
          ok: true,
          blob: () =>
            Promise.resolve(new Blob(["jpeg-bytes"], { type: "image/jpeg" })),
        } as Response);
      }
      return Promise.resolve({
        ok: true,
        blob: () => Promise.resolve(new Blob(["font-bytes"])),
      } as Response);
    });

    await exportReportToPdf(makeContent(), {
      defectImages: [
        { defectType: "균열", imageUrl: "/api/media/1/thumbnail" },
      ],
    });

    // mock autoTable은 didDrawCell을 직접 호출하지 않으므로(실제 페이지분할·좌표 계산은
    // jspdf-autotable 내부 동작이라 mock 밖의 관심사) 캡처한 콜백을 합성 셀 데이터로 직접
    // 호출해 "이미지 행에서만, 셀 좌표에 패딩을 두고" 그리는지 검증한다. rowPageBreak:'avoid'로
    // 표가 페이지를 넘을 때도 사진 1장이 중간에 잘리지 않게 보장한다(예전 방식은 고정 좌표
    // 계산이라 페이지 하단을 넘으면 그대로 잘려나갔다).
    const photoOptions = findTableOptions(
      (options) => typeof options.didDrawCell === "function",
    );
    expect(photoOptions?.rowPageBreak).toBe("avoid");

    const didDrawCell = photoOptions?.didDrawCell as (data: {
      section: string;
      row: { index: number };
      cell: { x: number; y: number; width: number; height: number };
    }) => void;
    didDrawCell({
      section: "body",
      row: { index: 0 },
      cell: { x: 23, y: 50, width: 164, height: 96 },
    });
    expect(mockAddImage).toHaveBeenCalledWith(
      expect.any(String),
      "JPEG",
      25,
      52,
      160,
      92,
    );

    mockAddImage.mockClear();
    // 캡션 행(홀수 인덱스)이나 head/foot 섹션에서는 이미지를 그리지 않는다.
    didDrawCell({
      section: "body",
      row: { index: 1 },
      cell: { x: 23, y: 150, width: 164, height: 9 },
    });
    expect(mockAddImage).not.toHaveBeenCalled();
  });

  it("사진 캡션은 하자 유형명 단독 표기가 아니라 등급·분석요약을 함께 붙인다", async () => {
    vi.mocked(fetch).mockImplementation((input) => {
      if (String(input) === "/api/media/1/thumbnail") {
        return Promise.resolve({
          ok: true,
          blob: () =>
            Promise.resolve(new Blob(["jpeg-bytes"], { type: "image/jpeg" })),
        } as Response);
      }
      return Promise.resolve({
        ok: true,
        blob: () => Promise.resolve(new Blob(["font-bytes"])),
      } as Response);
    });

    await exportReportToPdf(makeContent(), {
      defectImages: [
        {
          defectType: "균열",
          imageUrl: "/api/media/1/thumbnail",
          grade: "A",
          summary:
            "구조물의 내부 응력 집중 또는 외부 충격에 의해 발생했을 가능성이 있으며 지반 변형이 예상됨",
        },
      ],
    });

    const photoOptions = findTableOptions(
      (options) => typeof options.didDrawCell === "function",
    );
    const captionRow = (photoOptions?.body as { content: string }[][])[1];
    expect(captionRow[0].content).toBe(
      "< 균열(A등급) — 구조물의 내부 응력 집중 또는 외부 충격에 의해 발생했을 가능성이 있으며… >",
    );
  });

  it("등급·분석요약이 없으면(구버전 호출부) 유형명만이라도 하위호환으로 표기한다", async () => {
    vi.mocked(fetch).mockImplementation((input) => {
      if (String(input) === "/api/media/1/thumbnail") {
        return Promise.resolve({
          ok: true,
          blob: () =>
            Promise.resolve(new Blob(["jpeg-bytes"], { type: "image/jpeg" })),
        } as Response);
      }
      return Promise.resolve({
        ok: true,
        blob: () => Promise.resolve(new Blob(["font-bytes"])),
      } as Response);
    });

    await exportReportToPdf(makeContent(), {
      defectImages: [
        { defectType: "균열", imageUrl: "/api/media/1/thumbnail" },
      ],
    });

    const photoOptions = findTableOptions(
      (options) => typeof options.didDrawCell === "function",
    );
    const captionRow = (photoOptions?.body as { content: string }[][])[1];
    expect(captionRow[0].content).toBe("< 균열 >");
  });

  it("사진 캡션은 defectCount가 있어도 외 N건으로 합산하지 않는다", async () => {
    vi.mocked(fetch).mockImplementation((input) => {
      if (String(input) === "/api/media/1/thumbnail") {
        return Promise.resolve({
          ok: true,
          blob: () =>
            Promise.resolve(new Blob(["jpeg-bytes"], { type: "image/jpeg" })),
        } as Response);
      }
      return Promise.resolve({
        ok: true,
        blob: () => Promise.resolve(new Blob(["font-bytes"])),
      } as Response);
    });

    await exportReportToPdf(makeContent(), {
      defectImages: [
        {
          defectType: "균열",
          imageUrl: "/api/media/1/thumbnail",
          grade: "B",
          defectCount: 3,
        },
      ],
    });

    const photoOptions = findTableOptions(
      (options) => typeof options.didDrawCell === "function",
    );
    const captionRow = (photoOptions?.body as { content: string }[][])[1];
    expect(captionRow[0].content).toBe("< 균열(B등급) >");
    expect(captionRow[0].content).not.toContain("외");
  });

  it("축소본이 없으면 빈 사진 대지를 만들지 않는다", async () => {
    await exportReportToPdf(makeContent(), { defectImages: [] });

    const renderedText = mockText.mock.calls.map(([text]) => text).flat();
    expect(
      renderedText.some(
        (text) => typeof text === "string" && text.startsWith("부위별 사진"),
      ),
    ).toBe(false);
    expect(mockAddImage).not.toHaveBeenCalled();
  });

  it("context에 없는 작성 기준일은 현재 날짜로 채우지 않고 빈 값으로 둔다", async () => {
    await exportReportToPdf(makeContent());

    const options = findTableOptions(
      (candidate) =>
        Array.isArray(candidate.body) &&
        (candidate.body as string[][]).some((row) => row[0] === "작성 기준일"),
    );
    const row = (options?.body as string[][]).find(
      (candidate) => candidate[0] === "작성 기준일",
    );
    expect(row?.[1]).toBe("-");
  });

  it("미검증 법령 근거는 공식 근거처럼 표시하지 않고 미검증 표식을 붙인다", async () => {
    await exportReportToPdf(
      makeContent({
        recommendation: {
          items: [
            {
              target: "1층 벽체",
              method: "보수",
              priority: "중",
              legal_basis: "관련 근거 없음",
              legal_basis_verified: false,
            },
          ],
          monitoring_points: [],
        },
      }),
    );

    const options = findTableOptions(
      (candidate) =>
        Array.isArray(candidate.head) &&
        (candidate.head as string[][])[0]?.includes("적용 근거"),
    );
    expect(options?.body).toEqual([
      ["1", "1층 벽체", "보수", "중", "관련 근거 없음 (미검증)"],
    ]);
  });

  it("buildReportPdfFileName은 inspectionId와 오늘 날짜로 파일명을 만든다", () => {
    expect(buildReportPdfFileName(42)).toMatch(/^점검보고서_42_\d{8}\.pdf$/);
  });
});
