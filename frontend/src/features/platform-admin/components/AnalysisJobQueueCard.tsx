import {
  JOB_ID_TEXT_CLASS,
  JOB_STATUS_BADGE_CLASS,
  JOB_STATUS_DOT_CLASS,
  JOB_STATUS_LABEL,
  MONITORING_EMPTY_CELL,
} from '../monitoring.constants';
import type { AnalysisJobQueue } from '../monitoring.types';
import { RefreshIcon } from './icons/RefreshIcon';
import { StateRow } from './StateRow';

export const JOB_QUEUE_TEST_ID = 'analysis-job-queue';

const COL_COUNT = 5;

interface AnalysisJobQueueCardProps {
  queue?: AnalysisJobQueue;
  isLoading: boolean;
  isError: boolean;
  onRefresh: () => void;
  /** refetch() 진행 중 여부 — true인 동안 새로고침 버튼에 시각적 피드백(회전 아이콘)을 준다.
   * isLoading은 최초 로딩에만 true라 재조회 클릭 시엔 버튼이 아무 반응 없어 보이는 문제(#1408 후속)를 막는다. */
  isFetching?: boolean;
}

// 분석 잡 큐 — Figma node-id 1-404. 진행/완료/실패 요약 배지 + 최근 잡 목록 테이블.
// 백엔드 폴링 API가 아직 없어 "새로고침"은 refetch()를 그대로 호출한다(자동 폴링은 후속 범위).
export function AnalysisJobQueueCard({ queue, isLoading, isError, onRefresh, isFetching = false }: AnalysisJobQueueCardProps) {
  const jobs = queue?.jobs ?? [];

  return (
    <section className="flex flex-col gap-5 rounded-[20px] border border-border bg-surface p-6" data-testid={JOB_QUEUE_TEST_ID}>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-col gap-0.5">
          <h3 className="m-0 text-base font-bold text-heading">분석 잡 큐</h3>
          <span className="text-[11px] text-text-muted">당일 등록된 잡만 표시됩니다</span>
        </div>
        <button
          type="button"
          className="flex cursor-pointer items-center gap-1.5 text-[13px] font-medium text-text-muted hover:text-text-default disabled:cursor-not-allowed disabled:opacity-60"
          onClick={onRefresh}
          disabled={isFetching}
        >
          <span className={isFetching ? 'inline-flex animate-spin' : 'inline-flex'}>
            <RefreshIcon />
          </span>
          새로고침
        </button>
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <span className="inline-flex items-center gap-1.5 rounded-full bg-info-soft-bg px-3 py-1 text-xs font-semibold text-info-soft-fg">
          <span className="inline-block h-1.5 w-1.5 rounded-full bg-info-soft-fg" />
          진행 {isLoading || isError ? MONITORING_EMPTY_CELL : queue?.summary.inProgress ?? 0}
        </span>
        <span className="inline-flex items-center gap-1.5 rounded-full bg-[#dcfce7] px-3 py-1 text-xs font-semibold text-[#16a34a]">
          <span className="inline-block h-1.5 w-1.5 rounded-full bg-[#16a34a]" />
          완료 {isLoading || isError ? MONITORING_EMPTY_CELL : queue?.summary.completed ?? 0}
        </span>
        <span className="inline-flex items-center gap-1.5 rounded-full bg-danger-soft-bg px-3 py-1 text-xs font-semibold text-danger">
          <span className="inline-block h-1.5 w-1.5 rounded-full bg-danger" />
          실패 {isLoading || isError ? MONITORING_EMPTY_CELL : queue?.summary.failed ?? 0}
        </span>
      </div>

      <div className="overflow-x-auto">
        <table className="w-full min-w-[560px] border-collapse">
          <thead>
            <tr className="border-b border-border">
              <th className="whitespace-nowrap px-3 py-3 text-left text-xs font-medium text-text-muted">잡 ID</th>
              <th className="px-3 py-3 text-left text-xs font-medium text-text-muted">시설물</th>
              <th className="whitespace-nowrap px-3 py-3 text-right text-xs font-medium text-text-muted">이미지</th>
              <th className="whitespace-nowrap px-3 py-3 text-left text-xs font-medium text-text-muted">상태</th>
              <th className="whitespace-nowrap px-3 py-3 text-right text-xs font-medium text-text-muted">소요시간</th>
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
                  분석 잡 큐를 불러오지 못했습니다.
                </span>
              </StateRow>
            )}

            {!isLoading && !isError && jobs.length === 0 && (
              <StateRow colSpan={COL_COUNT}>
                <span className="text-text-muted">표시할 잡이 없습니다</span>
              </StateRow>
            )}

            {!isLoading &&
              !isError &&
              jobs.map((job) => (
                <tr key={job.id} className="border-b border-border last:border-b-0">
                  <td className={`whitespace-nowrap px-3 py-3 align-middle text-sm font-semibold ${JOB_ID_TEXT_CLASS[job.status]}`}>
                    {job.id}
                  </td>
                  <td
                    className="max-w-[280px] truncate px-3 py-3 align-middle text-sm text-text-default"
                    title={job.facilityAddress}
                  >
                    {job.facilityAddress}
                  </td>
                  <td className="whitespace-nowrap px-3 py-3 text-right align-middle text-sm text-text-default">
                    {job.imageCount.toLocaleString('ko-KR')}
                  </td>
                  <td className="whitespace-nowrap px-3 py-3 align-middle">
                    <span
                      className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold ${JOB_STATUS_BADGE_CLASS[job.status]}`}
                    >
                      <span className={`inline-block h-1.5 w-1.5 rounded-full ${JOB_STATUS_DOT_CLASS[job.status]}`} />
                      {JOB_STATUS_LABEL[job.status]}
                    </span>
                  </td>
                  <td className="whitespace-nowrap px-3 py-3 text-right align-middle text-sm text-text-muted">
                    {job.durationLabel ?? MONITORING_EMPTY_CELL}
                  </td>
                </tr>
              ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}
