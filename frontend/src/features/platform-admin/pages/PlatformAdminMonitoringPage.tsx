import { AnalysisJobQueueCard } from '../components/AnalysisJobQueueCard';
import { ErrorLogTable } from '../components/ErrorLogTable';
import { HfApiUsageCard } from '../components/HfApiUsageCard';
import { ServerHealthCards } from '../components/ServerHealthCards';
import { useSystemMonitoring } from '../hooks/useSystemMonitoring';
import { filterToLatestDay } from '../utils/filterToLatestDay';

// 플랫폼 관리자 > 시스템 모니터링(#729) — Figma node-id 1-404. 헤더(브레드크럼)·사이드바는
// PlatformAdminShellRoute가 담당하므로 이 페이지는 CONTENT 영역만 그린다(다른 platform-admin
// 페이지와 동일 계약). 분석 잡 큐·에러 로그는 최신 1일치만 노출한다(사용자 지시) — 큐 요약 배지
// (진행/완료/실패)는 별도 집계값이라 이 필터의 영향을 받지 않는다.
export function PlatformAdminMonitoringPage() {
  const { data, isLoading, isError, refetch } = useSystemMonitoring();

  const latestDayJobs = filterToLatestDay(data?.jobQueue.jobs ?? [], (job) => job.recordedAt);
  const latestDayErrorLogs = filterToLatestDay(data?.errorLogs ?? [], (log) => log.timestamp);

  return (
    <div className="flex min-h-full flex-col gap-6 bg-surface-muted p-6 sm:p-8">
      <ServerHealthCards items={data?.serverHealth ?? []} isLoading={isLoading} isError={isError} />

      {isError && (
        <p className="text-sm text-danger" role="alert">
          시스템 모니터링 정보를 불러오지 못했습니다.{' '}
          <button type="button" className="font-semibold underline" onClick={() => void refetch()}>
            다시 시도
          </button>
        </p>
      )}

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <AnalysisJobQueueCard
          queue={data ? { summary: data.jobQueue.summary, jobs: latestDayJobs } : undefined}
          isLoading={isLoading}
          isError={isError}
          onRefresh={() => void refetch()}
        />
        <HfApiUsageCard usage={data?.hfApiUsage} isLoading={isLoading} isError={isError} />
      </div>

      <section className="flex flex-col gap-4 rounded-[20px] border border-border bg-surface p-6">
        <div className="flex items-center justify-between">
          <h3 className="m-0 text-base font-bold text-heading">최근 에러 로그</h3>
          {/* 전체 로그 조회 화면은 이 이슈 범위 밖 — 우선 Figma 시안대로 어포던스만 노출한다(#729). */}
          <span className="flex items-center gap-1 text-[13px] font-medium text-text-muted">
            전체 보기
            <span aria-hidden>→</span>
          </span>
        </div>
        <ErrorLogTable logs={latestDayErrorLogs} isLoading={isLoading} isError={isError} />
      </section>
    </div>
  );
}
