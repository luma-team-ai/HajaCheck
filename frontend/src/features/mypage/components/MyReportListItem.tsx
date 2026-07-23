import type { MyReportCard } from '../types';
import { DownloadIcon } from './icons/DownloadIcon';
import { ReportDocumentIcon } from './icons/ReportDocumentIcon';
import { ReportGradeDots } from './ReportGradeDots';

type Props = {
  report: MyReportCard;
};

// 보고서 카드 한 줄 — "최근 발급된 보고서" 목록의 개별 항목(HAJA-366, #668, Figma 시안).
// 실 다운로드 엔드포인트(GET /api/reports/{id}/pdf/{storageKey})는 존재하지만 storageKey 등
// 필요한 필드가 이 mock 목록 API 스펙에 없어(이번 스코프는 목록 UI까지) 미리보기/다운로드는
// 항상 비활성으로 렌더한다 — 후속 이슈에서 실 API 연동.
export function MyReportListItem({ report }: Props) {
  return (
    <li className="flex flex-wrap items-center gap-4 rounded-xl bg-white px-4 py-3 shadow-sm">
      <ReportDocumentIcon />

      <div className="min-w-0 flex-1">
        <p className="m-0 truncate text-sm font-semibold text-heading">{report.title}</p>
        <p className="m-0 flex items-center gap-2 text-xs text-text-muted">
          <span>
            {report.issuedAt} · {report.fileSizeLabel}
          </span>
          <ReportGradeDots dots={report.gradeDots} />
        </p>
      </div>

      <div className="flex shrink-0 items-center gap-4">
        <button
          type="button"
          className="cursor-not-allowed text-sm font-medium text-primary opacity-60"
          disabled
          title="보고서 뷰어 연동 후 지원 예정(BE 미구현)"
        >
          미리보기
        </button>
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
