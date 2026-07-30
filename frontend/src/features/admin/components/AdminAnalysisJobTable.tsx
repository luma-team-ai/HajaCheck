import type { AdminAnalysisJob, AdminAnalysisJobStatus } from '../analysisJobTypes';
import { StateRow } from './StateRow';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner';

interface AdminAnalysisJobTableProps {
  jobs: AdminAnalysisJob[];
  isLoading: boolean;
  isError: boolean;
  onRetry: () => void;
}

const COLUMN_COUNT = 6;
const HEADER_CELL = 'px-4 py-3 text-left text-[13px] font-medium text-text-muted';
const BODY_CELL = 'px-4 py-3 align-middle';

const STATUS_BADGE: Record<AdminAnalysisJobStatus, { label: string; bg: string; fg: string; dot: string }> = {
  PENDING: { label: '대기', bg: '#F4F4F5', fg: '#7A7582', dot: '#A1A1AA' },
  ANALYZING: { label: '진행중', bg: '#E3F2FD', fg: '#1565C0', dot: '#2196F3' },
  COMPLETED: { label: '완료', bg: '#E8F5E9', fg: '#2E7D32', dot: '#4CAF50' },
};

// 관리자 AI 분석 현황 모니터링(신규) 표 — AdminUserTable과 동일한 feature-local 마크업 컨벤션
// (공통 Table은 행 액션·배지 셀을 표현 못 해 이 화면도 재사용하지 않는다).
export function AdminAnalysisJobTable({ jobs, isLoading, isError, onRetry }: AdminAnalysisJobTableProps) {
  return (
    <table className="w-full border-collapse text-sm">
      <thead>
        <tr className="border-b border-border">
          <th className={`${HEADER_CELL} pl-6`}>점검 ID</th>
          <th className={HEADER_CELL}>시설물명</th>
          <th className={HEADER_CELL}>담당 검사자</th>
          <th className={HEADER_CELL}>점검일</th>
          <th className={HEADER_CELL}>상태</th>
          <th className={`${HEADER_CELL} pr-6`}>진행률</th>
        </tr>
      </thead>
      <tbody>
        {isLoading && (
          <StateRow colSpan={COLUMN_COUNT}>
            <LoadingSpinner className="flex items-center justify-center gap-2" />
          </StateRow>
        )}

        {!isLoading && isError && (
          <StateRow colSpan={COLUMN_COUNT}>
            <span className="flex flex-col items-center gap-3" role="alert">
              <span className="text-danger">
                AI 분석 현황을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
              </span>
              <button
                type="button"
                className="cursor-pointer rounded-full border border-border bg-surface px-4 py-1.5 text-[13px] text-text-default hover:text-primary"
                onClick={onRetry}
              >
                다시 시도
              </button>
            </span>
          </StateRow>
        )}

        {!isLoading && !isError && jobs.length === 0 && (
          <StateRow colSpan={COLUMN_COUNT}>
            <span className="text-text-muted">조건에 맞는 분석 작업이 없습니다</span>
          </StateRow>
        )}

        {!isLoading &&
          !isError &&
          jobs.map((job) => {
            const badge = STATUS_BADGE[job.status];
            return (
              <tr key={job.jobId} className="border-b border-border last:border-b-0 hover:bg-surface-muted">
                <td className={`${BODY_CELL} pl-6 font-mono text-text-default`}>#{job.jobId}</td>
                <td className={`${BODY_CELL} text-heading`}>{job.facilityName}</td>
                <td className={`${BODY_CELL} text-text-default`}>{job.inspectorName}</td>
                <td className={`${BODY_CELL} text-text-default`}>{job.inspectionDate}</td>
                <td className={BODY_CELL}>
                  <span
                    className="inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs"
                    style={{ background: badge.bg, color: badge.fg }}
                  >
                    <span className="size-1.5 rounded-full" style={{ background: badge.dot }} aria-hidden />
                    {badge.label}
                  </span>
                </td>
                <td className={`${BODY_CELL} pr-6 text-text-default`}>
                  {job.progressPercent !== null ? `${job.progressPercent}%` : '-'}
                </td>
              </tr>
            );
          })}
      </tbody>
    </table>
  );
}
