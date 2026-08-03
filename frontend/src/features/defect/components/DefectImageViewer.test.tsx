// @vitest-environment jsdom
import { cleanup, render, screen } from "@testing-library/react";
import { fireEvent } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { calculateImageCanvasSize } from "../utils/defectImageGeometry";
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

describe("DefectImageViewer", () => {
  it("imageUrl이 있으면 이미지와 bbox 오버레이를 렌더링한다", () => {
    mockStageSize(800, 390);
    render(
      <DefectImageViewer
        imageUrl="/api/media/42/thumbnail"
        typeLabel="균열"
        bboxX={0.1}
        bboxY={0.2}
        bboxW={0.3}
        bboxH={0.4}
      />,
    );

    const img = screen.getByRole("img", {
      name: "균열 촬영 이미지",
    }) as HTMLImageElement;
    expect(img.src).toContain("/api/media/42/thumbnail");
    expect(screen.getByText("이미지를 불러오는 중입니다")).not.toBeNull();
    expect(screen.queryByLabelText("AI 감지 영역")).toBeNull();

    setNaturalSize(img, 1600, 1200);
    fireEvent.load(img);

    const canvas = screen.getByTestId("defect-image-canvas");
    expect(canvas.style.width).toBe("520px");
    expect(canvas.style.height).toBe("390px");
    expect(screen.queryByText("이미지를 불러오는 중입니다")).toBeNull();
    const overlay = screen.getByLabelText("AI 감지 영역");
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
        bboxX={null}
        bboxY={null}
        bboxW={null}
        bboxH={null}
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
    expect(screen.queryByLabelText("AI 감지 영역")).toBeNull();
  });

  it("bbox가 이미지 경계를 넘으면 캔버스 안으로 제한한다", () => {
    mockStageSize(800, 390);
    render(
      <DefectImageViewer
        imageUrl="/api/media/42/thumbnail"
        typeLabel="균열"
        bboxX={0.9}
        bboxY={-0.1}
        bboxW={0.4}
        bboxH={1.2}
      />,
    );

    const image = screen.getByRole("img", {
      name: "균열 촬영 이미지",
    }) as HTMLImageElement;
    setNaturalSize(image, 640, 360);
    fireEvent.load(image);

    const overlay = screen.getByLabelText("AI 감지 영역");
    expect(overlay.style.left).toBe("90%");
    expect(overlay.style.top).toBe("0%");
    expect(overlay.style.width).toBe("10%");
    expect(overlay.style.height).toBe("100%");
  });

  it("이미지 로딩이 실패하면 깨진 이미지 대신 오류 상태를 표시한다", () => {
    render(
      <DefectImageViewer
        imageUrl="/broken.jpg"
        typeLabel="균열"
        bboxX={0.1}
        bboxY={0.2}
        bboxW={0.3}
        bboxH={0.4}
      />,
    );

    fireEvent.error(screen.getByRole("img", { name: "균열 촬영 이미지" }));

    expect(screen.getByRole("alert").textContent).toBe(
      "이미지를 불러오지 못했습니다",
    );
    expect(screen.queryByRole("img")).toBeNull();
    expect(screen.queryByLabelText("AI 감지 영역")).toBeNull();
  });

  it("imageUrl이 없으면 빈 상태 메시지를 표시한다", () => {
    render(
      <DefectImageViewer
        imageUrl={null}
        typeLabel="균열"
        bboxX={null}
        bboxY={null}
        bboxW={null}
        bboxH={null}
      />,
    );

    expect(screen.getByText("촬영 이미지가 없습니다")).not.toBeNull();
    expect(screen.queryByRole("img")).toBeNull();
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
