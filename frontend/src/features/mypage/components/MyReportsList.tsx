import { LoadingSpinner } from '../../../shared/components/LoadingSpinner';
import type { MyReportCard } from '../types';
import { MyReportListItem } from './MyReportListItem';

type Props = {
  reports: MyReportCard[];
  isLoading: boolean;
  isError: boolean;
};

// "최근 발급된 보고서" 카드 목록 — 하단 회색 박스(Figma 시안, HAJA-366 #668). '내 보고서' 탭의
// 콘텐츠로 렌더한다.
export function MyReportsList({ reports, isLoading, isError }: Props) {
  return (
    <section className="flex flex-col gap-3 rounded-2xl bg-surface-muted p-4">
      <h3 className="m-0 text-sm font-semibold text-heading">최근 발급된 보고서</h3>

      {isLoading && <LoadingSpinner className="flex items-center justify-start gap-2 py-4" />}

      {isError && <p className="py-4 text-sm text-danger">보고서 목록을 불러오지 못했습니다.</p>}

      {!isLoading && !isError && reports.length === 0 && (
        <p className="py-4 text-sm text-text-muted">발급된 보고서가 없습니다.</p>
      )}

      {!isLoading && !isError && reports.length > 0 && (
        <ul className="m-0 flex list-none flex-col gap-2 p-0">
          {reports.map((report) => (
            <MyReportListItem key={report.id} report={report} />
          ))}
        </ul>
      )}
    </section>
  );
}
