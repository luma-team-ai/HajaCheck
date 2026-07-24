import { ERROR_LOG_LEVEL_BADGE_CLASS } from '../monitoring.constants';
import type { ErrorLogItem } from '../monitoring.types';
import { StateRow } from './StateRow';

export const ERROR_LOG_TABLE_TEST_ID = 'error-log-table';

const COL_COUNT = 4;

interface ErrorLogTableProps {
  logs: ErrorLogItem[];
  isLoading: boolean;
  isError: boolean;
}

// 최근 에러 로그 — Figma node-id 1-404. ERROR/WARN 레벨 배지 + 타임스탬프/서비스/메시지.
export function ErrorLogTable({ logs, isLoading, isError }: ErrorLogTableProps) {
  return (
    <div className="overflow-x-auto" data-testid={ERROR_LOG_TABLE_TEST_ID}>
      <table className="w-full min-w-[720px] border-collapse">
        <thead>
          <tr className="border-b border-border bg-surface-muted">
            <th className="px-4 py-3 text-left text-xs font-medium text-text-muted">TIMESTAMP</th>
            <th className="px-4 py-3 text-left text-xs font-medium text-text-muted">LEVEL</th>
            <th className="px-4 py-3 text-left text-xs font-medium text-text-muted">SERVICE</th>
            <th className="px-4 py-3 text-left text-xs font-medium text-text-muted">MESSAGE</th>
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
                에러 로그를 불러오지 못했습니다.
              </span>
            </StateRow>
          )}

          {!isLoading && !isError && logs.length === 0 && (
            <StateRow colSpan={COL_COUNT}>
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
        </tbody>
      </table>
    </div>
  );
}
