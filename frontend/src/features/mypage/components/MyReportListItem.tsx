import { Link } from 'react-router-dom';
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
// 연결한다(#1236). 다운로드는 실 엔드포인트(GET /api/reports/{id}/pdf/{storageKey})가 필요로 하는
// storageKey/pdfUrl이 이 목록 API(GET /api/me/reports) 응답에 없어 여전히 비활성 — BE 계약 확장이
// 선행돼야 하는 후속 건.
export function MyReportListItem({ report }: Props) {
  const title = formatReportTitle(report.facilityName, report.issuedAt, report.roundNo);
  const fileSizeLabel = formatFileSize(report.fileSizeBytes);

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
          className="inline-flex cursor-not-allowed items-center gap-1.5 rounded-full border border-border bg-white px-3 py-1.5 text-xs font-semibold text-text-default opacity-60"
          disabled
          title="다운로드 API 연동 후 지원 예정(BE 미구현)"
        >
          <DownloadIcon />
          다운로드
        </button>
      </div>
    </li>
  );
}
