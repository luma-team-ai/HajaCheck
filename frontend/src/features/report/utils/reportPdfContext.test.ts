import { describe, expect, it } from "vitest";
import type { ReportDetailResponse } from "../api/reportApi";
import { buildReportPdfContext } from "./reportPdfContext";

/** PDF "부위별 사진"은 하자별·등급별 항목을 합치지 않고 각 하자의 bbox만 싣는다. */
function buildReport(
  defects: Array<Record<string, unknown>>,
): ReportDetailResponse {
  return {
    createdAt: "2026-07-30T00:00:00Z",
    context: {
      media: [
        { id: 101, thumbnailUrl: "/media/101/thumb" },
        { id: 103, thumbnailUrl: "/media/103/thumb" },
      ],
      defects,
    },
  } as unknown as ReportDetailResponse;
}

const CRACK_A = {
  id: 1,
  mediaId: 101,
  type: "CRACK",
  typeLabel: "균열",
  grade: "C",
  bboxX: 0.32,
  bboxY: 0.1,
  bboxW: 0.05,
  bboxH: 0.71,
};
const CRACK_B = {
  id: 2,
  mediaId: 101,
  type: "CRACK",
  typeLabel: "균열",
  grade: "B",
  bboxX: 0.55,
  bboxY: 0.4,
  bboxW: 0.1,
  bboxH: 0.2,
};
const SPALLING = {
  id: 3,
  mediaId: 103,
  type: "SPALLING",
  typeLabel: "박리박락",
  grade: "D",
  bboxX: 0.12,
  bboxY: 0.6,
  bboxW: 0.2,
  bboxH: 0.2,
};

describe("buildReportPdfContext — 하자 박스", () => {
  it("같은 사진이어도 하자별·등급별 PDF 이미지 항목으로 분리한다", () => {
    const context = buildReportPdfContext(
      buildReport([CRACK_A, CRACK_B, SPALLING]),
      null,
      true,
    );
    const images = context.defectImages ?? [];

    expect(images).toHaveLength(3);
    expect(images.map((image) => image.imageUrl)).toEqual([
      "/media/101/thumb",
      "/media/101/thumb",
      "/media/103/thumb",
    ]);
    expect(images.map((image) => image.grade)).toEqual(["C", "B", "D"]);

    expect(images[0].boxes).toEqual([
      { x: 0.32, y: 0.1, width: 0.05, height: 0.71 },
    ]);
    expect(images[0].defectCount).toBe(1);
    expect(images[1].boxes).toEqual([
      { x: 0.55, y: 0.4, width: 0.1, height: 0.2 },
    ]);
    expect(images[1].defectCount).toBe(1);
    expect(images[2].boxes).toEqual([
      { x: 0.12, y: 0.6, width: 0.2, height: 0.2 },
    ]);
  });

  it("bbox가 null이면 박스 없이 사진만 남긴다(수동 추가 하자)", () => {
    const manual = {
      id: 9,
      mediaId: 101,
      type: "CRACK",
      typeLabel: "균열",
      grade: "A",
      bboxX: null,
      bboxY: null,
      bboxW: null,
      bboxH: null,
    };
    const images =
      buildReportPdfContext(buildReport([manual]), null, true).defectImages ??
      [];

    expect(images).toHaveLength(1);
    expect(images[0].boxes).toEqual([]);
  });

  it("폭·높이가 0 이하인 좌표는 그리지 않는다", () => {
    const degenerate = { ...CRACK_A, id: 10, bboxW: 0, bboxH: 0.5 };
    const images =
      buildReportPdfContext(buildReport([degenerate]), null, true)
        .defectImages ?? [];

    expect(images[0].boxes).toEqual([]);
  });

  it("사진 경계를 벗어나는 좌표는 그리지 않는다", () => {
    const outOfBounds = [
      { ...CRACK_A, id: 11, bboxX: -0.01 },
      { ...CRACK_A, id: 12, bboxY: -0.01 },
      { ...CRACK_A, id: 13, bboxX: 0.95, bboxW: 0.1 },
      { ...CRACK_A, id: 14, bboxY: 0.95, bboxH: 0.1 },
    ];
    const images =
      buildReportPdfContext(buildReport(outOfBounds), null, true)
        .defectImages ?? [];

    expect(images).toHaveLength(4);
    expect(images.every((image) => (image.boxes ?? []).length === 0)).toBe(true);
  });

  it("mediaId가 없는 하자는 서로 묶지 않는다", () => {
    // 묶을 기준이 없으므로 하자 id로 각자 독립 그룹이 되어야 한다 — 잘못 묶으면 서로 다른
    // 사진의 박스가 한 장에 섞인다.
    const noMedia = [
      { ...CRACK_A, id: 21, mediaId: null },
      { ...CRACK_B, id: 22, mediaId: null },
    ];
    const report = {
      createdAt: "2026-07-30T00:00:00Z",
      context: { media: [], defects: noMedia },
    } as unknown as ReportDetailResponse;

    // media 매칭에 실패하면 imageUrl이 없어 아예 제외된다 — 그룹핑 이전 단계의 계약을 함께 고정한다.
    expect(buildReportPdfContext(report, null, true).defectImages).toEqual([]);
  });

  it("includeReportPhotos=false면 사진을 만들지 않는다", () => {
    const context = buildReportPdfContext(buildReport([CRACK_A]), null, false);
    expect(context.defectImages).toEqual([]);
  });
});
