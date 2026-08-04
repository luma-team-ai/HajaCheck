// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ReportContent } from "../types";
import { mockReportDetailResponse } from "../mocks/reportDetail.mock";
import { exportReportToPdf, normalizeGradeInText } from "./exportReportToPdf";

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

/** `2. 결과 요약`의 단일 표(헤더 = 책임기술자 종합의견). */
function findSummaryTableOptions(): Record<string, unknown> | undefined {
  return findTableOptions(
    (options) =>
      JSON.stringify(options.head) ===
      JSON.stringify([["책임기술자 종합의견"]]),
  );
}

/** 결과 요약 표의 본문 행 텍스트(셀 정의 객체에서 content만 뽑는다). */
function summaryCellContents(
  options: Record<string, unknown> | undefined,
): string[] {
  const body = (options?.body ?? []) as { content: string }[][];
  return body.map(([cell]) => cell.content);
}

function findPhotoTableOptions(): Record<string, unknown> | undefined {
  return findTableOptions((options) => {
    if (typeof options.didDrawCell !== "function") return false;
    const body = options.body as unknown;
    return Array.isArray(body) && JSON.stringify(body).includes("< ");
  });
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
    // 원본은 진단 외관조사결과·보수ㆍ보강을 별도 절이 아니라 `2. 결과 요약`의 소절로 묶는다.
    expect(renderedText).toContain("가. 진단 외관조사결과 기본사항");
    expect(renderedText).toContain("나. 보수ㆍ보강(안)");
    expect(renderedText).not.toContain("3. 진단 외관조사결과 기본사항");
    // 소절로 내려간 블록의 자체 소절은 한 단계 더 내려간다(가./나. → 1)/2)).
    expect(renderedText).toContain("1) 지속 관찰 부위");
    // 블록 제목과 같은 이름의 소절 제목을 또 달지 않는다(`보수ㆍ보강(안)` 중복 표기 방지).
    expect(renderedText.filter((text) => text.includes("보수ㆍ보강(안)"))).toEqual(
      ["나. 보수ㆍ보강(안)"],
    );
    // 지원되지 않는 서명·참여자 필드는 만들지 않는다(수동 섹션을 추가하지 않은 기본 상태).
    expect(renderedText).not.toContain("제  출  문");
    expect(renderedText).not.toContain("작성자");
    expect(renderedText).not.toContain("입회자");
  });

  it("기본현황 나.는 원본처럼 `점검 실시결과 현황`을 기존 하자·권고 데이터에서 파생해 채운다", async () => {
    await exportReportToPdf(makeContent());

    const renderedText = mockText.mock.calls.map(([text]) => text).flat();
    expect(renderedText).toContain("나. 점검 실시결과 현황");
    expect(renderedText).toContain("다. 참고사항");
    expect(renderedText).not.toContain("나. 점검 개요");

    const options = findTableOptions((candidate) =>
      JSON.stringify(candidate.body).includes("중대한 결함 등"),
    );
    expect(options?.body).toEqual([
      // C등급뿐이라 중대한 결함은 없음, 공중이용부위는 미입력이라 행 자체를 생략한다.
      ["중대한 결함 등", "없음"],
      // 목록 표기는 문서 전체가 `ㆍ` 하나로 통일된다(`-`/`1)`/`//` 혼용 금지).
      [
        "점검 주요결과",
        "금회 조사 결과 주요 결함은 다음과 같습니다.\nㆍ1층 벽체 : 균열 1건",
      ],
      ["주요 보수ㆍ보강", "ㆍ중 : 보수"],
    ]);
  });

  it("공중이 이용하는 부위의 결함은 편집기 수동 입력값을 그대로 쓰고 자동 판정하지 않는다", async () => {
    const content = makeContent();
    await exportReportToPdf({
      ...content,
      overview: { ...content.overview, public_use_area_defect: "3층 보도 난간 파손" },
    });

    const options = findTableOptions((candidate) =>
      JSON.stringify(candidate.body).includes("중대한 결함 등"),
    );
    expect(options?.body).toEqual(
      expect.arrayContaining([
        ["공중이 이용하는\n부위의 결함", "3층 보도 난간 파손"],
      ]),
    );
  });

  it("공중이 이용하는 부위의 결함이 미입력이면 해당 행을 렌더링하지 않는다", async () => {
    const content = makeContent();

    // undefined인 경우
    await exportReportToPdf({
      ...content,
      overview: { ...content.overview, public_use_area_defect: undefined },
    });

    const optionsUndefined = findTableOptions((candidate) =>
      JSON.stringify(candidate.body).includes("중대한 결함 등"),
    );
    const bodyUndefined = JSON.stringify(optionsUndefined?.body ?? []);
    expect(bodyUndefined).not.toContain("공중이 이용하는");

    // 공백만 있는 경우
    await exportReportToPdf({
      ...content,
      overview: { ...content.overview, public_use_area_defect: "   " },
    });

    const optionsBlank = findTableOptions((candidate) =>
      JSON.stringify(candidate.body).includes("중대한 결함 등"),
    );
    const bodyBlank = JSON.stringify(optionsBlank?.body ?? []);
    expect(bodyBlank).not.toContain("공중이 이용하는");
  });


  it("결과 요약은 소절 없이 `책임기술자 종합의견` 표 하나로 렌더링하고 하단에 서명란을 붙인다", async () => {
    await exportReportToPdf(makeContent(), {
      responsibleEngineerName: "김기준",
    });

    // 원본 서식대로 가./나./다. 소절 제목을 만들지 않는다.
    const renderedText = mockText.mock.calls.map(([text]) => text).flat();
    expect(renderedText).not.toContain("가. 책임기술자 종합의견");
    expect(renderedText).not.toContain("나. 결함 등급별 현황");
    expect(renderedText).not.toContain("다. 주요 발견사항");

    const summaryOptions = findSummaryTableOptions();
    expect(summaryOptions?.head).toEqual([["책임기술자 종합의견"]]);
    // 종합의견·주요 발견사항·등급별 건수가 공용 불릿(`ㆍ`) + 문단 사이 한 줄로 합쳐지고,
    // 서명은 본문에 겹쳐 그리지 않고 아래 행으로 분리된다(긴 의견에서 마지막 줄과 겹침 방지).
    expect(summaryCellContents(summaryOptions)).toEqual([
      "ㆍ양호\n\nㆍ균열 발견\n\nㆍ금회 조사 결과 확인된 결함은 총 1건으로, 등급별로는 a 0건, b 0건, c 1건, d 0건, e 0건으로 조사되었습니다.",
      "책임기술자 : 김 기 준    (서명)",
    ]);
    // 두 행 모두 칸막이 괘선 없이 한 상자로 보이게 한다(외곽선은 tableLineWidth가 그림).
    expect(summaryOptions?.bodyStyles).toEqual(
      expect.objectContaining({ lineWidth: 0 }),
    );
    expect(summaryOptions?.didDrawCell).toBeUndefined();
    expect(mockAddImage).not.toHaveBeenCalled();
  });

  it("결과 요약 시작 지점이 페이지 하단에 걸리면 표 헤더가 고아로 남지 않도록 새 페이지로 넘긴다", async () => {
    // 기본현황 블록의 표 3개 중 마지막(다. 참고사항) 표의 finalY만 220으로 만들어, 이어지는
    // `2. 결과 요약`의 시작 지점(cursorY≈230)이 페이지 하단(BOTTOM_LIMIT=274) 근처에 걸리게
    // 만든다. 이 값은 예전 MIN_BLOCK_SPACE(40)로는 "페이지에 더 들어간다"고 판단해 절 제목+표
    // 헤더까지는 그리고 본문(책임기술자 종합의견 내용)만 다음 페이지로 넘어가버리는 고아 현상을
    // 재현하는 지점이다 — renderSummaryBlock의 SUMMARY_BLOCK_MIN_HEIGHT 가드가 없으면 이 테스트는
    // "2. 결과 요약" 제목이 y≈230(페이지 하단)에 그려져 실패한다.
    mockAutoTable.mockImplementationOnce((doc: MockJsPDF) => {
      doc.lastAutoTable = { finalY: 120 };
    });
    mockAutoTable.mockImplementationOnce((doc: MockJsPDF) => {
      doc.lastAutoTable = { finalY: 120 };
    });
    mockAutoTable.mockImplementationOnce((doc: MockJsPDF) => {
      doc.lastAutoTable = { finalY: 220 };
    });

    await exportReportToPdf(makeContent());

    const summaryTitleCall = mockText.mock.calls.find(
      ([text]) => text === "2. 결과 요약",
    ) as [string, number, number] | undefined;
    expect(summaryTitleCall).toBeDefined();
    const [, , summaryTitleY] = summaryTitleCall!;

    // 표 헤더만 겨우 들어가는 하단(230mm)이 아니라, 새 페이지 상단(MARGIN_X=23mm)에서 시작해야
    // 표 헤더와 본문이 분리되지 않는다.
    expect(summaryTitleY).toBe(23);
    expect(mockAddPage).toHaveBeenCalledTimes(1);
  });

  it("책임기술자 서명란 이름은 수동 입력값을 담당자 fallback보다 우선한다", async () => {
    const content = makeContent();
    await exportReportToPdf(
      {
        ...content,
        summary: {
          ...content.summary,
          responsible_engineer_name: "박수정",
        },
      },
      { responsibleEngineerName: "김기준" },
    );

    expect(summaryCellContents(findSummaryTableOptions())).toContain(
      "책임기술자 : 박 수 정    (서명)",
    );
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
    // 제출문은 원본에서도 번호 없는 커버 페이지라 절 번호를 소비하지 않고, 결과 요약 소절로
    // 내려간 진단 외관조사결과·보수ㆍ보강도 번호를 쓰지 않는다 → 기본현황1·결과요약2·명단3.
    expect(renderedText).toContain("1. 기본현황");
    expect(renderedText).toContain("3. 참여기술진 명단");

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

  it("위치도ㆍ전경 사진ㆍ종ㆍ평면도ㆍ현황도는 편집기에 저장된 base64 이미지를 사진과 같은 형식(사진+캡션)으로 렌더링한다", async () => {
    const content = makeContent({
      manualSections: [
        {
          id: "manual-location-1",
          type: "location-drawing-photos",
          title: "위치도ㆍ전경 사진ㆍ종ㆍ평면도ㆍ현황도",
          data: {
            images: [
              { dataUrl: "data:image/jpeg;base64,AAA", caption: "한남대교 위치도" },
              { dataUrl: "data:image/jpeg;base64,BBB", caption: "" },
            ],
          },
        },
      ],
      sectionOrder: [
        "overview",
        "summary",
        "detail",
        "recommendation",
        "manual-location-1",
      ],
    });

    await exportReportToPdf(content);

    const renderedText = mockText.mock.calls.map(([text]) => text).flat();
    // 폰트 로딩 외에는 어떤 media 엔드포인트도 fetch하지 않는다 — 부위별 사진(defectImages)과
    // 달리 이 섹션은 편집기에서 이미 완성된 data URL을 들고 있다(#1409).
    const fetchedUrls = vi.mocked(fetch).mock.calls.map(([url]) => String(url));
    expect(fetchedUrls.some((url) => url.includes("/api/media"))).toBe(false);

    // 사진 1장 = 표 1개(사진 표와 동일한 구조). 캡션이 없으면 "이미지"로 폴백한다.
    const photoTables = mockAutoTable.mock.calls.filter(([, options]) =>
      JSON.stringify((options as Record<string, unknown>).body).includes("< "),
    );
    expect(photoTables).toHaveLength(2);
    // overview=1, summary=2(진단 외관조사결과·보수ㆍ보강은 그 소절로 편입돼 번호를 안 씀) →
    // 이 섹션은 sectionOrder상 summary 이후에 와도 소절 대상이 아니므로 다음 절 번호 3을 받는다.
    expect(renderedText).toContain("3. 위치도ㆍ전경 사진ㆍ종ㆍ평면도ㆍ현황도");

    const [, firstOptions] = photoTables[0] as [unknown, Record<string, unknown>];
    const didDrawCell = firstOptions.didDrawCell as (data: {
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
      "data:image/jpeg;base64,AAA",
      "JPEG",
      25,
      52,
      160,
      92,
    );
  });

  it("위치도ㆍ전경 사진ㆍ종ㆍ평면도ㆍ현황도에 이미지가 없으면 다른 수동 섹션처럼 빈 상태를 안내한다", async () => {
    const content = makeContent({
      manualSections: [
        {
          id: "manual-location-1",
          type: "location-drawing-photos",
          title: "위치도ㆍ전경 사진ㆍ종ㆍ평면도ㆍ현황도",
          data: { images: [] },
        },
      ],
      sectionOrder: [
        "overview",
        "summary",
        "detail",
        "recommendation",
        "manual-location-1",
      ],
    });

    await exportReportToPdf(content);

    const options = findTableOptions((candidate) =>
      JSON.stringify(candidate.body).includes("추가된 이미지가 없습니다."),
    );
    expect(options?.body).toEqual([["추가된 이미지가 없습니다."]]);
  });

  it("결함 사진도 다른 섹션과 동등하게 sectionOrder로 자유롭게 재배치된다", async () => {
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
    expect(renderedText).toContain("1. 결함 사진");
    expect(renderedText).toContain("2. 기본현황");
  });

  it("사진이 0장이면 sectionOrder에 있어도 결함 사진 섹션과 번호를 만들지 않는다", async () => {
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
        (text) => typeof text === "string" && text.includes("결함 사진"),
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
        (candidate.head as string[][])[1]?.includes("결함종류"),
    );
    // "연번"·"결함발생 부재"(구조부재명 데이터가 없어 시설물 주소를 대신 표시해왔던 컬럼)는
    // 원본 양식에 없거나 채울 데이터가 없어 뺐다(#1499) — 상태평가·결함종류·조사 결과·추정
    // 원인 4개 컬럼만 남는다.
    expect(options?.body).toEqual([["c", "균열", "설명", "원인"]]);
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

    // 상태평가 결과 바는 이제 컬럼 헤더 행과 같은 표의 head 첫 행이라(페이지 넘김 시 함께
    // 반복시키기 위함, PR머신 리뷰 취지 반영) body가 아니라 head[0]에서 찾는다.
    const options = findTableOptions(
      (candidate) =>
        Array.isArray(candidate.head) &&
        (candidate.head as string[][])[1]?.includes("결함종류"),
    );
    const barRow = (options?.head as { content: string }[][])[0];
    expect(barRow[1].content).toBe("상태평가 결과 : d");
  });

  it("점검 축소본을 결함 사진 표 안에 안전하게 배치한다(자동 페이지분할되는 autoTable 셀)", async () => {
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
    const photoOptions = findPhotoTableOptions();
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

  it("사진이 남은 지면에 통째로 못 들어가면 표 윗선만 걸치지 않고 새 페이지에서 시작한다", async () => {
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
    // 앞 섹션들이 페이지 하단(220mm)까지 차 있는 상황 — 사진 1장(109mm)이 들어갈 자리가 없다.
    mockAutoTable.mockImplementation((doc: MockJsPDF) => {
      doc.lastAutoTable = { finalY: 220 };
    });

    await exportReportToPdf(makeContent(), {
      defectImages: [
        { defectType: "균열", imageUrl: "/api/media/1/thumbnail" },
        { defectType: "박리", imageUrl: "/api/media/1/thumbnail" },
      ],
    });

    // 사진마다 표를 끊고(장수만큼 표 호출), 자리가 없으면 페이지를 넘긴 뒤 그린다.
    const photoTables = mockAutoTable.mock.calls.filter(([, options]) =>
      JSON.stringify((options as Record<string, unknown>).body).includes("< "),
    );
    expect(photoTables).toHaveLength(2);
    expect(mockAddPage).toHaveBeenCalled();

    mockAutoTable.mockImplementation((doc: MockJsPDF) => {
      doc.lastAutoTable = { finalY: 120 };
    });
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

    const photoOptions = findPhotoTableOptions();
    const captionRow = (photoOptions?.body as { content: string }[][])[1];
    // 원본 양식 관례(소문자 단일 글자)를 따라 "등급 A"가 아니라 "a"로 표기한다(#1499 후속).
    expect(captionRow[0].content).toBe(
      "< 균열 (a) — 구조물의 내부 응력 집중 또는 외부 충격에 의해 발생했을 가능성이 있으며… >",
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

    const photoOptions = findPhotoTableOptions();
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

    const photoOptions = findPhotoTableOptions();
    const captionRow = (photoOptions?.body as { content: string }[][])[1];
    expect(captionRow[0].content).toBe("< 균열 (b) >");
    expect(captionRow[0].content).not.toContain("외");
  });

  it("축소본이 없으면 빈 사진 대지를 만들지 않는다", async () => {
    await exportReportToPdf(makeContent(), { defectImages: [] });

    const renderedText = mockText.mock.calls.map(([text]) => text).flat();
    expect(
      renderedText.some(
        (text) => typeof text === "string" && text.startsWith("결함 사진"),
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
    // "연번"은 레퍼런스 양식에 없어 뺐다(#1499 후속).
    expect(options?.body).toEqual([
      ["1층 벽체", "보수", "중", "관련 근거 없음 (미검증)"],
    ]);
  });
});

// normalizeGradeInText 정규식 범위를 고정한다.
// 이 테스트가 없으면 [A-E] 범위가 [A-Z]로 복귀하거나 [A-D]로 축소돼도 감지되지 않는다.
describe("normalizeGradeInText", () => {
  it("(등급 X) 표기를 소문자 단일 글자 (x) 로 정규화한다", () => {
    expect(normalizeGradeInText("(등급 A)")).toBe(" (A)");
    expect(normalizeGradeInText("(등급 C)")).toBe(" (C)");
    expect(normalizeGradeInText("(등급 E)")).toBe(" (E)");
  });

  it("(X등급) 표기를 소문자 단일 글자 (x) 로 정규화한다", () => {
    expect(normalizeGradeInText("(A등급)")).toBe(" (A)");
    expect(normalizeGradeInText("(E등급)")).toBe(" (E)");
  });

  it("독립 (X) 표기를 소문자로 정규화한다 — 모든 하자 등급 A~E를 커버한다", () => {
    for (const grade of ["A", "B", "C", "D", "E"]) {
      expect(normalizeGradeInText(`(${grade})`)).toBe(` (${grade.toLowerCase()})`);
    }
  });

  it("등급 범위 밖의 대문자 괄호 표기 (F), (X), (Z) 는 변환하지 않는다", () => {
    expect(normalizeGradeInText("(F)")).toBe("(F)");
    expect(normalizeGradeInText("(X)")).toBe("(X)");
    expect(normalizeGradeInText("(Z)")).toBe("(Z)");
  });

  it("이미 소문자인 (a)~(e)는 그대로 유지한다", () => {
    for (const grade of ["a", "b", "c", "d", "e"]) {
      const input = ` (${grade})`;
      expect(normalizeGradeInText(input)).toBe(input);
    }
  });
});
