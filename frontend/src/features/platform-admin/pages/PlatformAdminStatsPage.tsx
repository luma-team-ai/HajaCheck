import { useState } from 'react';
import { Button } from '../../../shared/components/Button';
import { AnalysisRequestTrendChart } from '../components/AnalysisRequestTrendChart';
import { CounselTypeDistributionChart } from '../components/CounselTypeDistributionChart';
import { DownloadIcon } from '../components/icons/DownloadIcon';
import { MonthlySummaryTable } from '../components/MonthlySummaryTable';
import { PlanDistributionChart } from '../components/PlanDistributionChart';
import { ServiceStatsKpiCards } from '../components/ServiceStatsKpiCards';
import { StatsPeriodFilterSelect, type StatsPeriodFilterValue } from '../components/StatsPeriodFilterSelect';
import { SubscriberTrendChart } from '../components/SubscriberTrendChart';
import { useServiceStats } from '../hooks/useServiceStats';

// 플랫폼 관리자 > 서비스 통계(#634) — Figma node-id 177-3515. 헤더(브레드크럼)·사이드바는
// PlatformAdminShellRoute가 담당하므로 이 페이지는 CONTENT 영역만 그린다(다른 platform-admin
// 페이지와 동일 계약). "내보내기"는 백엔드 내보내기 API 없이(#634 범위 밖) 브라우저 인쇄 경로로
// 처리한다 — features/admin AdminUsersPage의 기존 내보내기 패턴과 동일 전략.
export function PlatformAdminStatsPage() {
  const [period, setPeriod] = useState<StatsPeriodFilterValue>('6M');
  const { data, isLoading, isError, refetch } = useServiceStats();

  function handleExport() {
    window.print();
  }

  return (
    <div className="flex min-h-full flex-col bg-surface-muted p-6 sm:p-8">
      <div className="flex flex-col gap-8 rounded-[20px] border border-border bg-surface p-6 sm:p-8">
        <div className="flex flex-wrap items-start justify-between gap-4 border-b border-border pb-6">
          <h1 className="m-0 text-2xl font-bold text-heading">서비스 통계</h1>
          <div className="flex items-center gap-3">
            <StatsPeriodFilterSelect value={period} onChange={setPeriod} />
            <Button variant="secondary" size="sm" onClick={handleExport}>
              <DownloadIcon />
              내보내기
            </Button>
          </div>
        </div>

        <ServiceStatsKpiCards kpi={data?.kpi} isLoading={isLoading} isError={isError} />

        {isError && (
          <p className="text-sm text-danger" role="alert">
            서비스 통계를 불러오지 못했습니다.{' '}
            <button
              type="button"
              className="font-semibold underline"
              onClick={() => void refetch()}
            >
              다시 시도
            </button>
          </p>
        )}

        <div className="grid grid-cols-1 gap-8 lg:grid-cols-2">
          <div>
            <h3 className="mb-4 text-base font-bold text-heading">가입자 추이</h3>
            <SubscriberTrendChart data={data?.subscriberTrend ?? []} isLoading={isLoading} isError={isError} />
          </div>
          <div>
            <h3 className="mb-4 text-base font-bold text-heading">분석 요청 추이</h3>
            <AnalysisRequestTrendChart
              data={data?.analysisRequestTrend ?? []}
              isLoading={isLoading}
              isError={isError}
            />
          </div>
        </div>

        <div className="grid grid-cols-1 gap-8 border-t border-border pt-8 lg:grid-cols-2">
          <div>
            <h3 className="mb-4 text-base font-bold text-heading">플랜 분포</h3>
            <PlanDistributionChart data={data?.planDistribution ?? []} isLoading={isLoading} isError={isError} />
          </div>
          <div>
            <h3 className="mb-4 text-base font-bold text-heading">상담 유형 분포</h3>
            <CounselTypeDistributionChart
              data={data?.counselTypeDistribution ?? []}
              isLoading={isLoading}
              isError={isError}
            />
          </div>
        </div>

        <div className="border-t border-border pt-8">
          <h3 className="mb-4 text-base font-bold text-heading">월별 요약</h3>
          <MonthlySummaryTable rows={data?.monthlySummary ?? []} isLoading={isLoading} isError={isError} />
        </div>
      </div>
    </div>
  );
}
