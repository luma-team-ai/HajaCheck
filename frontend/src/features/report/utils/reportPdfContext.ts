import type { InspectionResult } from '../../inspection/types';
import type { ReportDetailResponse } from '../api/reportApi';
import type { ReportPdfBox, ReportPdfContext, ReportPdfImage } from './exportReportToPdf';

function buildDefectImageSummary(defect: NonNullable<ReportDetailResponse['context']>['defects'][number]): string {
  const parts = [
    defect.location,
    defect.crackWidthMm ? `균열폭 ${defect.crackWidthMm}mm` : null,
    defect.crackLengthMm ? `길이 ${defect.crackLengthMm}mm` : null,
    defect.areaRatio ? `면적비 ${Math.round(defect.areaRatio * 100)}%` : null,
    defect.confidence ? `신뢰도 ${Math.round(defect.confidence * 100)}%` : null,
  ].filter(Boolean);
  return parts.join(' · ');
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
    typeof x !== 'number' || !Number.isFinite(x) ||
    typeof y !== 'number' || !Number.isFinite(y) ||
    typeof width !== 'number' || !Number.isFinite(width) ||
    typeof height !== 'number' || !Number.isFinite(height)
  ) {
    return null;
  }
  if (width <= 0 || height <= 0) return null;
  return { x, y, width, height };
}

interface PdfImageEntry {
  key: string;
  image: Omit<ReportPdfImage, 'boxes' | 'defectCount'>;
  box: ReportPdfBox | null;
}

/**
 * 하자를 사진 단위로 묶어 PDF 이미지 목록을 만든다(#1333).
 *
 * <p>이전에는 하자 1건당 이미지 1장을 만들어, 같은 사진에서 하자가 여러 건 나오면 **같은 사진이
 * 그 수만큼 중복**됐다(박스도 없어 서로 구분되지 않았다). 이제 사진 1장에 박스를 모두 얹는다.
 * 사진이 처음 등장한 순서는 그대로 유지한다 — 캡션·본문 순서와 어긋나지 않게 하기 위함이다.
 */
function groupPhotos(entries: PdfImageEntry[]): ReportPdfImage[] {
  const grouped: ReportPdfImage[] = [];
  const indexByKey = new Map<string, number>();

  entries.forEach(({ key, image, box }) => {
    const existing = indexByKey.get(key);
    if (existing === undefined) {
      indexByKey.set(key, grouped.length);
      grouped.push({ ...image, boxes: box ? [box] : [], defectCount: 1 });
      return;
    }
    const target = grouped[existing];
    if (box) target.boxes = [...(target.boxes ?? []), box];
    target.defectCount = (target.defectCount ?? 1) + 1;
  });

  return grouped;
}

/** mediaId 가 없으면(수동 추가 하자) 묶을 기준이 없으므로 하자 id 로 각자 독립 그룹이 된다. */
function photoKey(mediaId: number | null | undefined, defectId: number): string {
  return mediaId != null ? `media-${mediaId}` : `defect-${defectId}`;
}

export function buildReportPdfContext(
  report: ReportDetailResponse,
  inspectionData: InspectionResult | null | undefined,
  includeReportPhotos: boolean,
  fallback?: { facilityName?: string; inspectionRound?: number },
): ReportPdfContext {
  const mediaById = new Map((report.context?.media ?? []).map((media) => [media.id, media]));
  const contextImages: ReportPdfImage[] = includeReportPhotos
    ? groupPhotos(
        (report.context?.defects ?? []).flatMap<PdfImageEntry>((defect) => {
          const media = defect.mediaId ? mediaById.get(defect.mediaId) : undefined;
          const imageUrl = media?.thumbnailUrl ?? media?.detailUrl;
          if (!imageUrl) return [];
          return [{
            key: photoKey(defect.mediaId, defect.id),
            image: {
              defectType: defect.typeLabel ?? defect.type,
              imageUrl,
              grade: defect.grade ?? undefined,
              summary: buildDefectImageSummary(defect),
            },
            box: toPdfBox(defect.bboxX, defect.bboxY, defect.bboxW, defect.bboxH),
          }];
        }),
      )
    : [];
  const fallbackImages: ReportPdfImage[] = includeReportPhotos
    ? groupPhotos(
        inspectionData?.defects.flatMap<PdfImageEntry>((defect) =>
          defect.thumbnailUrl ? [{
            key: photoKey(defect.mediaId, defect.id),
            image: {
              defectType: defect.type,
              imageUrl: defect.thumbnailUrl,
              grade: defect.grade,
              summary: defect.summary,
            },
            box: toPdfBox(defect.bbox?.x, defect.bbox?.y, defect.bbox?.width, defect.bbox?.height),
          }] : [],
        ) ?? [],
      )
    : [];

  return {
    facilityName: report.context?.facility?.name ?? inspectionData?.facilityName ?? fallback?.facilityName,
    inspectionRound: report.context?.inspection?.roundNo ?? inspectionData?.roundNo ?? fallback?.inspectionRound,
    issuedAt: new Date(report.createdAt),
    defectImages: contextImages.length > 0 ? contextImages : fallbackImages,
  };
}
