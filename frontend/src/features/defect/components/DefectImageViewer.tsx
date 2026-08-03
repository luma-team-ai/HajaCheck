import { useLayoutEffect, useMemo, useRef, useState } from "react";
import type { SyntheticEvent } from "react";
import {
  calculateImageCanvasSize,
  getBboxArea,
  getBboxStyle,
} from "../utils/defectImageGeometry";
import type { Size } from "../utils/defectImageGeometry";
import type { Defect } from "../types";
import { formatDefectCode } from "../utils/defectFormat";

type Props = {
  imageUrl: string | null;
  typeLabel: string;
  defects?: Defect[];
  selectedDefectId?: number;
  onSelectDefect?: (defectId: number) => void;
};

type LoadedImage = Size & {
  src: string;
};

// 하자 실사진 표시(HAJA-314) — bbox 좌표는 0~1 비율이라 %로 변환해 오버레이 위치를 잡는다.
// 백엔드가 media_id 없는(아직 이미지가 연결되지 않은) 하자를 그대로 반환할 수 있어(imageUrl=null),
// 그 경우 빈 상태를 명시적으로 보여준다 — 깨진 이미지 아이콘을 노출하지 않는다.
export function DefectImageViewer({
  imageUrl,
  typeLabel,
  defects = [],
  selectedDefectId,
  onSelectDefect,
}: Props) {
  const stageRef = useRef<HTMLDivElement>(null);
  const [stageSize, setStageSize] = useState<Size>({ width: 0, height: 0 });
  const [loadedImage, setLoadedImage] = useState<LoadedImage | null>(null);
  const [failedImageUrl, setFailedImageUrl] = useState<string | null>(null);
  const isLoaded = imageUrl != null && loadedImage?.src === imageUrl;
  const hasLoadError = imageUrl != null && failedImageUrl === imageUrl;
  const drawableDefects = useMemo(
    () =>
      defects
        .map((defect) => ({
          defect,
          area: getBboxArea(defect.bboxX, defect.bboxY, defect.bboxW, defect.bboxH),
          style: getBboxStyle(defect.bboxX, defect.bboxY, defect.bboxW, defect.bboxH),
        }))
        .filter((item) => item.area != null && item.style != null)
        .sort((a, b) => (b.area ?? 0) - (a.area ?? 0)),
    [defects],
  );
  const canvasSize =
    isLoaded && loadedImage
      ? calculateImageCanvasSize(
          { width: loadedImage.width, height: loadedImage.height },
          stageSize,
        )
      : null;

  useLayoutEffect(() => {
    const stage = stageRef.current;
    if (!stage) return;

    const updateStageSize = (width: number, height: number) => {
      setStageSize((current) =>
        current.width === width && current.height === height
          ? current
          : { width, height },
      );
    };
    const updateFromElement = () => {
      const rect = stage.getBoundingClientRect();
      updateStageSize(rect.width, rect.height);
    };

    updateFromElement();
    if (typeof ResizeObserver === "undefined") {
      window.addEventListener("resize", updateFromElement);
      return () => window.removeEventListener("resize", updateFromElement);
    }

    const observer = new ResizeObserver(([entry]) => {
      updateStageSize(entry.contentRect.width, entry.contentRect.height);
    });
    observer.observe(stage);
    return () => observer.disconnect();
  }, []);

  const handleImageLoad = (event: SyntheticEvent<HTMLImageElement>) => {
    const image = event.currentTarget;
    setFailedImageUrl(null);
    setLoadedImage({
      src: imageUrl ?? "",
      width: image.naturalWidth,
      height: image.naturalHeight,
    });
  };

  const handleImageError = () => {
    setLoadedImage(null);
    setFailedImageUrl(imageUrl);
  };

  return (
    <section className="defect-card defect-viewer" aria-label="하자 이미지">
      <div ref={stageRef} className="defect-image-stage">
        {imageUrl ? (
          <>
            {!isLoaded && !hasLoadError && (
              <div className="defect-image-empty" role="status">
                이미지를 불러오는 중입니다
              </div>
            )}
            {!hasLoadError && (
              <div
                className={`defect-image-canvas${isLoaded ? " is-loaded" : ""}`}
                data-testid="defect-image-canvas"
                style={
                  canvasSize
                    ? { width: canvasSize.width, height: canvasSize.height }
                    : undefined
                }
              >
                {/* 이미지 생명주기 이벤트는 사용자 상호작용이 아니며, 명시적 로딩·실패 상태에 필요하다. */}
                {/* eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions */}
                <img
                  key={imageUrl}
                  src={imageUrl}
                  alt={`${typeLabel} 촬영 이미지`}
                  onLoad={handleImageLoad}
                  onError={handleImageError}
                />
                {isLoaded &&
                  drawableDefects.map(({ defect, style }) => {
                    const isSelected = defect.id === selectedDefectId;
                    return (
                      <button
                        key={defect.id}
                        type="button"
                        className={`defect-detection-box${isSelected ? " is-selected" : ""}`}
                        aria-label={`${formatDefectCode(defect.id)} ${defect.typeLabel} 하자 영역 선택`}
                        aria-pressed={isSelected}
                        style={{ ...style, zIndex: isSelected ? 2 : 1 }}
                        onClick={() => onSelectDefect?.(defect.id)}
                      />
                    );
                  })}
              </div>
            )}
            {hasLoadError && (
              <div className="defect-image-empty" role="alert">
                이미지를 불러오지 못했습니다
              </div>
            )}
          </>
        ) : (
          <div className="defect-image-empty" role="status">
            촬영 이미지가 없습니다
          </div>
        )}
      </div>
    </section>
  );
}
