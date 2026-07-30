import { ERROR_LOG_LEVEL_BADGE_CLASS } from '../monitoring.constants';
import type { ErrorLogItem } from '../monitoring.types';
import { StateRow } from './StateRow';

export const ERROR_LOG_TABLE_TEST_ID = 'error-log-table';

const COL_COUNT = 4;
// 페이지당 표시 건수(pageSize)만큼 항상 같은 행 수를 유지해, 조회된 건수가 적을 때 목록 높이가
// 줄어들지 않도록 빈 행으로 채운다(사용자 지시 — "데이터 건수에 따라 줄어들고 늘어나고 있다").
// 실제 데이터 행 하나의 대략 높이(px) — 로딩/에러/빈 상태 메시지도 이 기준으로 같은 높이를 맞춘다.
const ROW_HEIGHT_PX = 45;

interface ErrorLogTableProps {
  logs: ErrorLogItem[];
  isLoading: boolean;
  isError: boolean;
  pageSize: number;
}

// 에러 로그 — Figma node-id 1-404. ERROR/WARN 레벨 배지 + 타임스탬프/서비스/메시지.
export function ErrorLogTable({ logs, isLoading, isError, pageSize }: ErrorLogTableProps) {
  const fillerRowCount = !isLoading && !isError && logs.length > 0 ? Math.max(0, pageSize - logs.length) : 0;
  const stateRowMinHeightPx = pageSize * ROW_HEIGHT_PX;

  return (
    <div className="overflow-x-auto" data-testid={ERROR_LOG_TABLE_TEST_ID}>
      <table className="w-full min-w-[720px] border-collapse">
        <thead>
          <tr className="border-b border-border bg-surface-muted">
            <th className="px-4 py-3 text-left text-xs font-medium text-text-muted">시간</th>
            <th className="px-4 py-3 text-left text-xs font-medium text-text-muted">LEVEL</th>
            <th className="px-4 py-3 text-left text-xs font-medium text-text-muted">SERVICE</th>
            <th className="px-4 py-3 text-left text-xs font-medium text-text-muted">메시지</th>
          </tr>
        </thead>
        <tbody>
          {isLoading && (
            <StateRow colSpan={COL_COUNT} minHeightPx={stateRowMinHeightPx}>
              <span className="text-text-muted">불러오는 중...</span>
            </StateRow>
          )}

          {!isLoading && isError && (
            <StateRow colSpan={COL_COUNT} minHeightPx={stateRowMinHeightPx}>
              <span className="text-danger" role="alert">
                에러 로그를 불러오지 못했습니다.
              </span>
            </StateRow>
          )}

          {!isLoading && !isError && logs.length === 0 && (
            <StateRow colSpan={COL_COUNT} minHeightPx={stateRowMinHeightPx}>
              <span className="text-text-muted">표시할 로그가 없습니다</span>
            </StateRow>
          )}

          {!isLoading &&
            !isError &&
            logs.map((log) => (
              <tr key={log.id} className="border-b border-border last:border-b-0">
                <td className="whitespace-nowrap px-4 py-3 align-middle font-mono text-[13px] text-text-muted">
                  {log.timestamp}
                </td>
                <td className="px-4 py-3 align-middle">
                  <span
                    className={`inline-flex items-center rounded-md px-2 py-0.5 text-[11px] font-bold ${ERROR_LOG_LEVEL_BADGE_CLASS[log.level]}`}
                  >
                    {log.level}
                  </span>
                </td>
                <td className="whitespace-nowrap px-4 py-3 align-middle font-mono text-[13px] text-text-default">
                  {log.service}
                </td>
                <td className="px-4 py-3 align-middle text-[13px] text-text-default">{log.message}</td>
              </tr>
            ))}

          {fillerRowCount > 0 &&
            Array.from({ length: fillerRowCount }, (_, index) => (
              <tr key={`filler-${index}`} aria-hidden="true">
                <td className="px-4 py-3" style={{ height: ROW_HEIGHT_PX }} colSpan={COL_COUNT}>
                  &nbsp;
                </td>
              </tr>
            ))}
        </tbody>
      </table>
    </div>
  );
}
