import type { InspectionResult } from '../../inspection/types';
import type { ReportDetailResponse } from '../api/reportApi';
import type { ReportPdfContext, ReportPdfImage } from './exportReportToPdf';

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

export function buildReportPdfContext(
  report: ReportDetailResponse,
  inspectionData: InspectionResult | null | undefined,
  includeReportPhotos: boolean,
  fallback?: { facilityName?: string; inspectionRound?: number },
): ReportPdfContext {
  const mediaById = new Map((report.context?.media ?? []).map((media) => [media.id, media]));
  const contextImages: ReportPdfImage[] = includeReportPhotos
    ? (report.context?.defects ?? []).flatMap((defect) => {
        const media = defect.mediaId ? mediaById.get(defect.mediaId) : undefined;
        const imageUrl = media?.thumbnailUrl ?? media?.detailUrl;
        if (!imageUrl) return [];
        return [{
          defectType: defect.typeLabel ?? defect.type,
          imageUrl,
          grade: defect.grade ?? undefined,
          summary: buildDefectImageSummary(defect),
        }];
      })
    : [];
  const fallbackImages: ReportPdfImage[] = includeReportPhotos
    ? inspectionData?.defects.flatMap((defect) =>
        defect.thumbnailUrl ? [{
          defectType: defect.type,
          imageUrl: defect.thumbnailUrl,
          grade: defect.grade,
          summary: defect.summary,
        }] : [],
      ) ?? []
    : [];

  return {
    facilityName: report.context?.facility?.name ?? inspectionData?.facilityName ?? fallback?.facilityName,
    inspectionRound: report.context?.inspection?.roundNo ?? inspectionData?.roundNo ?? fallback?.inspectionRound,
    issuedAt: new Date(report.createdAt),
    defectImages: contextImages.length > 0 ? contextImages : fallbackImages,
  };
}
