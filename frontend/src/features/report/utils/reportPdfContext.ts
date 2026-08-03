import type { InspectionResult } from "../../inspection/types";
import type { ReportDetailResponse } from "../api/reportApi";
import type {
  ReportPdfBox,
  ReportPdfContext,
  ReportPdfImage,
} from "./exportReportToPdf";

function buildDefectImageSummary(
  defect: NonNullable<ReportDetailResponse["context"]>["defects"][number],
): string {
  const parts = [
    defect.location,
    defect.crackWidthMm ? `균열폭 ${defect.crackWidthMm}mm` : null,
    defect.crackLengthMm ? `길이 ${defect.crackLengthMm}mm` : null,
    // 균열은 선형 하자라 면적비가 항상 1% 미만대로 작다(ai-server grading.py 균열 전용 구간표
    // 참고, 등급 임계값 자체가 0.275%~0.969%) — 소수점 1자리로도 0.0%로 뭉개지는 값이 있어(실측
    // 확인) 3자리까지 늘려 실제로 측정된 작은 값임을 드러낸다.
    defect.areaRatio ? `면적비 ${(defect.areaRatio * 100).toFixed(3)}%` : null,
    defect.confidence ? `신뢰도 ${Math.round(defect.confidence * 100)}%` : null,
  ].filter(Boolean);
  return parts.join(" · ");
}

/**
 * 0~1 정규화 좌표만 박스로 인정한다(#1333) — 백엔드는 수동 추가 하자에 null 을 주고,
 * 범위를 벗어나거나 폭·높이가 0 이하인 값은 PDF 에서 사진 밖으로 삐져나가거나 안 보인다.
 */
function toPdfBox(
  x?: number | null,
  y?: number | null,
  width?: number | null,
  height?: number | null,
): ReportPdfBox | null {
  if (
    typeof x !== "number" ||
    !Number.isFinite(x) ||
    typeof y !== "number" ||
    !Number.isFinite(y) ||
    typeof width !== "number" ||
    !Number.isFinite(width) ||
    typeof height !== "number" ||
    !Number.isFinite(height)
  ) {
    return null;
  }
  if (x < 0 || y < 0 || width <= 0 || height <= 0) return null;
  if (x + width > 1 || y + height > 1) return null;
  return { x, y, width, height };
}

interface PdfImageEntry {
  image: Omit<ReportPdfImage, "boxes" | "defectCount">;
  box: ReportPdfBox | null;
}

/**
 * 하자를 PDF 사진 항목으로 변환한다.
 *
 * <p>편집 화면의 전체 사진 미리보기는 같은 사진의 여러 박스를 한 장에 모아도 되지만,
 * PDF 본문은 하자별·등급별 항목이 합쳐지면 안 된다. 같은 mediaId라도 하자 1건당 사진 항목
 * 1개를 유지해 캡션과 박스가 서로 다른 등급/유형을 `외 N건`으로 뭉뚱그리지 않게 한다.
 */
function toPdfImages(entries: PdfImageEntry[]): ReportPdfImage[] {
  return entries.map(({ image, box }) => ({
    ...image,
    boxes: box ? [box] : [],
    defectCount: 1,
  }));
}

export function buildReportPdfContext(
  report: ReportDetailResponse,
  inspectionData: InspectionResult | null | undefined,
  includeReportPhotos: boolean,
  fallback?: { facilityName?: string; inspectionRound?: number },
): ReportPdfContext {
  const mediaById = new Map(
    (report.context?.media ?? []).map((media) => [media.id, media]),
  );
  const contextImages: ReportPdfImage[] = includeReportPhotos
    ? toPdfImages(
        (report.context?.defects ?? []).flatMap<PdfImageEntry>((defect) => {
          const media = defect.mediaId
            ? mediaById.get(defect.mediaId)
            : undefined;
          const imageUrl = media?.thumbnailUrl ?? media?.detailUrl;
          if (!imageUrl) return [];
          return [
            {
              image: {
                defectType: defect.typeLabel ?? defect.type,
                imageUrl,
                grade: defect.grade ?? undefined,
                summary: buildDefectImageSummary(defect),
              },
              box: toPdfBox(
                defect.bboxX,
                defect.bboxY,
                defect.bboxW,
                defect.bboxH,
              ),
            },
          ];
        }),
      )
    : [];
  const fallbackImages: ReportPdfImage[] = includeReportPhotos
    ? toPdfImages(
        inspectionData?.defects.flatMap<PdfImageEntry>((defect) =>
          defect.thumbnailUrl
            ? [
                {
                  image: {
                    defectType: defect.type,
                    imageUrl: defect.thumbnailUrl,
                    // 등급 미판정(null)은 캡션에서 등급을 생략한다 — "null등급" 표기 방지(#1395)
                    grade: defect.grade ?? undefined,
                    summary: defect.summary,
                  },
                  box: toPdfBox(
                    defect.bbox?.x,
                    defect.bbox?.y,
                    defect.bbox?.width,
                    defect.bbox?.height,
                  ),
                },
              ]
            : [],
        ) ?? [],
      )
    : [];

  return {
    facilityName:
      report.context?.facility?.name ??
      inspectionData?.facilityName ??
      fallback?.facilityName,
    inspectionRound:
      report.context?.inspection?.roundNo ??
      inspectionData?.roundNo ??
      fallback?.inspectionRound,
    issuedAt: new Date(report.createdAt),
    responsibleEngineerName: report.context?.assignedInspector?.name ?? undefined,
    defectImages: contextImages.length > 0 ? contextImages : fallbackImages,
  };
}
