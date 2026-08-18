// @vitest-environment jsdom
import { cleanup, render, screen, within } from "@testing-library/react";
import { fireEvent } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { calculateImageCanvasSize } from "../utils/defectImageGeometry";
import type { InspectionDefect } from "../types";
import { DefectImageViewer } from "./DefectImageViewer";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

function setNaturalSize(
  image: HTMLImageElement,
  width: number,
  height: number,
): void {
  Object.defineProperties(image, {
    naturalWidth: { configurable: true, value: width },
    naturalHeight: { configurable: true, value: height },
  });
}

function mockStageSize(width: number, height: number): void {
  vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockReturnValue({
    width,
    height,
    top: 0,
    right: width,
    bottom: height,
    left: 0,
    x: 0,
    y: 0,
    toJSON: () => ({}),
  });
}

function makeDefect(
  id: number,
  overrides: Partial<InspectionDefect> = {},
): InspectionDefect {
  return {
    id,
    inspectionId: 1,
    type: "CRACK",
    typeLabel: "균열",
    grade: "C",
    status: "CONFIRMED",
    confidence: 0.9,
    reviewed: true,
    bboxX: 0.1,
    bboxY: 0.2,
    bboxW: 0.3,
    bboxH: 0.4,
    crackWidthMm: null,
    crackLengthMm: null,
    areaRatio: null,
    areaMm2: null,
    mediaId: 42,
    imageUrl: "/api/media/42/thumbnail",
    detailUrl: "/api/media/42/detail",
    createdAt: "2026-08-01T00:00:00Z",
    ...overrides,
  };
}

describe("DefectImageViewer", () => {
  it("imageUrl이 있으면 이미지와 bbox 오버레이를 렌더링한다", () => {
    mockStageSize(800, 390);
    render(
      <DefectImageViewer
        imageUrl="/api/media/42/thumbnail"
        typeLabel="균열"
        defects={[makeDefect(1)]}
        selectedDefectId={1}
      />,
    );

    const img = screen.getByRole("img", {
      name: "균열 촬영 이미지",
    }) as HTMLImageElement;
    expect(img.src).toContain("/api/media/42/thumbnail");
    expect(screen.getByText("이미지를 불러오는 중입니다")).not.toBeNull();
    expect(screen.queryByRole("button", { name: "DEF-0001 균열 하자 영역 선택" })).toBeNull();

    setNaturalSize(img, 1600, 1200);
    fireEvent.load(img);

    const canvas = screen.getByTestId("defect-image-canvas");
    expect(canvas.style.width).toBe("520px");
    expect(canvas.style.height).toBe("390px");
    expect(screen.queryByText("이미지를 불러오는 중입니다")).toBeNull();
    const overlay = screen.getByRole("button", { name: "DEF-0001 균열 하자 영역 선택" });
    expect(overlay.style.left).toBe("10%");
    expect(overlay.style.top).toBe("20%");
    expect(overlay.style.width).toBe("30%");
    expect(overlay.style.height).toBe("40%");
  });

  it("bbox가 없으면 오버레이 없이 이미지만 렌더링한다", () => {
    mockStageSize(800, 390);
    render(
      <DefectImageViewer
        imageUrl="/api/media/42/thumbnail"
        typeLabel="균열"
        defects={[makeDefect(1, { bboxX: null, bboxY: null, bboxW: null, bboxH: null })]}
      />,
    );

    const image = screen.getByRole("img", {
      name: "균열 촬영 이미지",
    }) as HTMLImageElement;
    setNaturalSize(image, 320, 180);
    fireEvent.load(image);

    const canvas = screen.getByTestId("defect-image-canvas");
    expect(canvas.style.width).toBe("320px");
    expect(canvas.style.height).toBe("180px");
    expect(screen.queryByRole("button", { name: /하자 영역 선택/ })).toBeNull();
  });

  it("bbox가 이미지 경계를 넘으면 캔버스 안으로 제한한다", () => {
    mockStageSize(800, 390);
    render(
      <DefectImageViewer
        imageUrl="/api/media/42/thumbnail"
        typeLabel="균열"
        defects={[makeDefect(1, { bboxX: 0.9, bboxY: -0.1, bboxW: 0.4, bboxH: 1.2 })]}
      />,
    );

    const image = screen.getByRole("img", {
      name: "균열 촬영 이미지",
    }) as HTMLImageElement;
    setNaturalSize(image, 640, 360);
    fireEvent.load(image);

    const overlay = screen.getByRole("button", { name: "DEF-0001 균열 하자 영역 선택" });
    expect(overlay.style.left).toBe("90%");
    expect(overlay.style.top).toBe("0%");
    expect(overlay.style.width).toBe("10%");
    expect(overlay.style.height).toBe("100%");
  });

  it("이미지 로딩이 실패해도 모든 하자를 선택할 수 있다", () => {
    const onSelectDefect = vi.fn();
    render(
      <DefectImageViewer
        imageUrl="/broken.jpg"
        typeLabel="균열"
        defects={[makeDefect(1), makeDefect(2, { typeLabel: "박리·박락" })]}
        selectedDefectId={1}
        onSelectDefect={onSelectDefect}
      />,
    );

    fireEvent.error(screen.getByRole("img", { name: "균열 촬영 이미지" }));

    expect(screen.getByRole("alert").textContent).toBe(
      "이미지를 불러오지 못했습니다",
    );
    expect(screen.queryByRole("img")).toBeNull();
    expect(screen.queryByRole("button", { name: /하자 영역 선택/ })).toBeNull();
    const selector = screen.getByLabelText("이미지 내 하자 선택");
    fireEvent.click(within(selector).getByRole("button", { name: "DEF-0002 · 박리·박락" }));
    expect(onSelectDefect).toHaveBeenCalledWith(2);
  });

  it("imageUrl이 없으면 빈 상태 메시지를 표시한다", () => {
    render(
      <DefectImageViewer
        imageUrl={null}
        typeLabel="균열"
      />,
    );

    expect(screen.getByText("촬영 이미지가 없습니다")).not.toBeNull();
    expect(screen.queryByRole("img")).toBeNull();
  });

  it("같은 이미지의 모든 bbox를 버튼으로 표시하고 선택 시 콜백을 호출한다", () => {
    mockStageSize(800, 390);
    const onSelectDefect = vi.fn();
    render(
      <DefectImageViewer
        imageUrl="/api/media/42/thumbnail"
        typeLabel="균열"
        defects={[
          makeDefect(1, { bboxX: 0.1, bboxY: 0.1, bboxW: 0.6, bboxH: 0.6 }),
          makeDefect(2, { type: "SPALLING", typeLabel: "박리·박락", bboxX: 0.2, bboxY: 0.2, bboxW: 0.1, bboxH: 0.1 }),
        ]}
        selectedDefectId={1}
        onSelectDefect={onSelectDefect}
      />,
    );

    const image = screen.getByRole("img", { name: "균열 촬영 이미지" }) as HTMLImageElement;
    setNaturalSize(image, 640, 480);
    fireEvent.load(image);

    expect(screen.getByRole("button", { name: "DEF-0001 균열 하자 영역 선택" }).getAttribute("aria-pressed")).toBe("true");
    const secondBox = screen.getByRole("button", { name: "DEF-0002 박리·박락 하자 영역 선택" });
    const boxes = screen
      .getAllByRole("button")
      .filter((element) => element.classList.contains("defect-detection-box"));
    expect(boxes.map((element) => element.getAttribute("aria-label"))).toEqual([
      "DEF-0001 균열 하자 영역 선택",
      "DEF-0002 박리·박락 하자 영역 선택",
    ]);
    expect(boxes[0].style.zIndex).toBe("");
    expect(boxes[1].style.zIndex).toBe("");
    fireEvent.click(secondBox);
    expect(onSelectDefect).toHaveBeenCalledWith(2);
  });

  it("bbox가 완전히 같아도 상시 선택 목록에서 각 하자를 선택할 수 있다", () => {
    mockStageSize(800, 390);
    const onSelectDefect = vi.fn();
    render(
      <DefectImageViewer
        imageUrl="/api/media/42/detail"
        typeLabel="균열"
        defects={[
          makeDefect(1, { bboxX: 0.2, bboxY: 0.2, bboxW: 0.3, bboxH: 0.3 }),
          makeDefect(2, { typeLabel: "박리·박락", bboxX: 0.2, bboxY: 0.2, bboxW: 0.3, bboxH: 0.3 }),
        ]}
        selectedDefectId={1}
        onSelectDefect={onSelectDefect}
      />,
    );

    const image = screen.getByRole("img", { name: "균열 촬영 이미지" }) as HTMLImageElement;
    setNaturalSize(image, 640, 480);
    fireEvent.load(image);
    expect(screen.getAllByRole("button", { name: /하자 영역 선택/ })).toHaveLength(2);

    const selector = screen.getByLabelText("이미지 내 하자 선택");
    fireEvent.click(within(selector).getByRole("button", { name: "DEF-0002 · 박리·박락" }));
    expect(onSelectDefect).toHaveBeenCalledWith(2);
  });
});

describe("calculateImageCanvasSize", () => {
  it("원본보다 큰 영역에서도 이미지를 확대하지 않는다", () => {
    expect(
      calculateImageCanvasSize(
        { width: 320, height: 180 },
        { width: 1200, height: 800 },
      ),
    ).toEqual({ width: 320, height: 180 });
  });

  it("가로·세로 제한 중 더 작은 배율로 원본 비율을 유지한다", () => {
    expect(
      calculateImageCanvasSize(
        { width: 1600, height: 1200 },
        { width: 800, height: 390 },
      ),
    ).toEqual({ width: 520, height: 390 });
  });
});
