import { useState } from 'react';
import { Link } from 'react-router-dom';
import { AlertModal } from '../../../shared/components/Modal';
import { buildReportPdfFileName, normalizePdfPreviewUrl } from '../../../shared/utils/reportPdf';
import type { MyReportCard } from '../types';
import { formatFileSize, formatIssuedDate, formatReportTitle } from '../utils/myInspectionsFormat';
import { DownloadIcon } from './icons/DownloadIcon';
import { ReportDocumentIcon } from './icons/ReportDocumentIcon';
import { ReportGradeDots } from './ReportGradeDots';

type Props = {
  report: MyReportCard;
};

// 보고서 카드 한 줄 — "최근 발급된 보고서" 목록의 개별 항목(HAJA-366/#668, BE 연동 #844/HAJA-442).
// 미리보기는 회사 보고서 목록(ReportListTable)과 동일하게 /reports/:reportId(ReportGeneratePage)로
// 연결한다(#1236). 다운로드는 목록 API(GET /api/me/reports) 응답의 pdfUrl(#1464)을
// normalizePdfPreviewUrl(레거시 "localhost:8080/..." 포맷 정규화, #1186/#1235)을 거쳐
// fetch → blob → objectURL 방식으로 내려받는다(ReportGeneratePage#handleDownloadStoredPdf와 동일 패턴).
export function MyReportListItem({ report }: Props) {
  const title = formatReportTitle(report.facilityName, report.issuedAt, report.roundNo);
  const fileSizeLabel = formatFileSize(report.fileSizeBytes);
  const [isDownloading, setIsDownloading] = useState(false);
  const [downloadError, setDownloadError] = useState<string | null>(null);
  const canDownload = report.pdfUrl != null;

  const handleDownload = async () => {
    if (!report.pdfUrl || isDownloading) return;
    setIsDownloading(true);
    try {
      const response = await fetch(normalizePdfPreviewUrl(report.pdfUrl), { credentials: 'include' });
      if (!response.ok) throw new Error(`PDF ${response.status}`);
      const blob = await response.blob();
      const objectUrl = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = objectUrl;
      anchor.download = buildReportPdfFileName(report.inspectionId);
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      URL.revokeObjectURL(objectUrl);
    } catch {
      setDownloadError('PDF 다운로드에 실패했습니다.\n잠시 후 다시 시도해주세요.');
    } finally {
      setIsDownloading(false);
    }
  };

  return (
    <li className="flex flex-wrap items-center gap-4 rounded-xl bg-white px-4 py-3 shadow-sm">
      <ReportDocumentIcon />

      <div className="min-w-0 flex-1">
        <p className="m-0 truncate text-sm font-semibold text-heading">{title}</p>
        <p className="m-0 flex items-center gap-2 text-xs text-text-muted">
          <span>
            {formatIssuedDate(report.issuedAt)}
            {/* fileSizeBytes가 null(PDF 조회 실패)이면 크기 표시 자체를 감춘다(handoff §2-3) */}
            {fileSizeLabel != null && ` · ${fileSizeLabel}`}
          </span>
          <ReportGradeDots dots={report.gradeDots} />
        </p>
      </div>

      <div className="flex shrink-0 items-center gap-4">
        <Link
          to={`/reports/${report.id}`}
          className="text-sm font-medium text-primary no-underline hover:underline"
        >
          미리보기
        </Link>
        <button
          type="button"
          onClick={handleDownload}
          className={`inline-flex items-center gap-1.5 rounded-full border border-border bg-white px-3 py-1.5 text-xs font-semibold text-text-default ${
            canDownload && !isDownloading ? '' : 'cursor-not-allowed opacity-60'
          }`}
          disabled={!canDownload || isDownloading}
          title={canDownload ? undefined : '다운로드 가능한 PDF가 없습니다'}
        >
          <DownloadIcon />
          다운로드
        </button>
      </div>

      <AlertModal
        open={downloadError != null}
        title="다운로드 실패"
        message={downloadError ?? ''}
        onClose={() => setDownloadError(null)}
      />
    </li>
  );
}
