import { MONTHLY_TREND_ARROW, MONTHLY_TREND_LABEL } from '../stats.constants';
import type { MonthlySummaryRow } from '../stats.types';
import { StateRow } from './StateRow';

const COL_COUNT = 6;

interface MonthlySummaryTableProps {
  rows: MonthlySummaryRow[];
  isLoading: boolean;
  isError: boolean;
}

export function MonthlySummaryTable({ rows, isLoading, isError }: MonthlySummaryTableProps) {
  return (
    <table className="w-full border-collapse">
      <thead>
        <tr className="border-b border-border">
          <th className="px-4 py-3 text-left text-xs font-medium text-text-muted">월</th>
          <th className="px-4 py-3 text-right text-xs font-medium text-text-muted">신규 가입</th>
          <th className="px-4 py-3 text-right text-xs font-medium text-text-muted">분석 장수</th>
          <th className="px-4 py-3 text-right text-xs font-medium text-text-muted">상담 건수</th>
          <th className="px-4 py-3 text-right text-xs font-medium text-text-muted">전환(Free→Standard)</th>
          <th className="px-4 py-3 text-right text-xs font-medium text-text-muted">추세</th>
        </tr>
      </thead>
      <tbody>
        {isLoading && (
          <StateRow colSpan={COL_COUNT}>
            <span className="text-text-muted">불러오는 중...</span>
          </StateRow>
        )}

        {!isLoading && isError && (
          <StateRow colSpan={COL_COUNT}>
            <span className="text-danger" role="alert">
              월별 요약을 불러오지 못했습니다.
            </span>
          </StateRow>
        )}

        {!isLoading && !isError && rows.length === 0 && (
          <StateRow colSpan={COL_COUNT}>
            <span className="text-text-muted">표시할 데이터가 없습니다</span>
          </StateRow>
        )}

        {!isLoading &&
          !isError &&
          rows.map((row) => (
            <tr key={row.month} className="border-b border-border last:border-b-0">
              <td className="px-4 py-3 align-middle text-sm font-semibold text-heading">{row.month}</td>
              <td className="px-4 py-3 text-right align-middle text-sm text-text-default">
                {row.newSubscribers.toLocaleString('ko-KR')}
              </td>
              <td className="px-4 py-3 text-right align-middle text-sm text-text-default">
                {row.analysisCount.toLocaleString('ko-KR')}
              </td>
              <td className="px-4 py-3 text-right align-middle text-sm text-text-default">
                {row.counselCount.toLocaleString('ko-KR')}
              </td>
              <td className="px-4 py-3 text-right align-middle text-sm text-text-default">
                {row.freeToStandardConversions.toLocaleString('ko-KR')}
              </td>
              <td className="px-4 py-3 text-right align-middle text-sm">
                <span aria-label={MONTHLY_TREND_LABEL[row.trend]}>{MONTHLY_TREND_ARROW[row.trend]}</span>
              </td>
            </tr>
          ))}
      </tbody>
    </table>
  );
}
