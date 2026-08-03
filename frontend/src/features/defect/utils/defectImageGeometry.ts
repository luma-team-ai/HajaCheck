import type { CSSProperties } from "react";

export type Size = {
  width: number;
  height: number;
};

export function calculateImageCanvasSize(
  naturalSize: Size,
  availableSize: Size,
): Size | null {
  if (
    naturalSize.width <= 0 ||
    naturalSize.height <= 0 ||
    availableSize.width <= 0 ||
    availableSize.height <= 0
  ) {
    return null;
  }

  const scale = Math.min(
    1,
    availableSize.width / naturalSize.width,
    availableSize.height / naturalSize.height,
  );

  return {
    width: naturalSize.width * scale,
    height: naturalSize.height * scale,
  };
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max);
}

function toPercent(value: number): string {
  return `${Number((value * 100).toFixed(6))}%`;
}

export function getBboxStyle(
  bboxX: number | null,
  bboxY: number | null,
  bboxW: number | null,
  bboxH: number | null,
): CSSProperties | null {
  if (
    bboxX == null ||
    bboxY == null ||
    bboxW == null ||
    bboxH == null ||
    ![bboxX, bboxY, bboxW, bboxH].every(Number.isFinite)
  ) {
    return null;
  }

  const left = clamp(bboxX, 0, 1);
  const top = clamp(bboxY, 0, 1);
  const width = clamp(bboxW, 0, 1 - left);
  const height = clamp(bboxH, 0, 1 - top);

  return {
    top: toPercent(top),
    left: toPercent(left),
    width: toPercent(width),
    height: toPercent(height),
  };
}
