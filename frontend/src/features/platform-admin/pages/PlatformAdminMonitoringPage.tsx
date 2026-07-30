import { useRef, useState } from 'react';
import { Pagination } from '../../../shared/components/Pagination';
import { AnalysisJobQueueCard } from '../components/AnalysisJobQueueCard';
import { ErrorLogFilterBar } from '../components/ErrorLogFilterBar';
import type { ErrorLogLevelFilter } from '../components/ErrorLogFilterBar';
import { ErrorLogTable } from '../components/ErrorLogTable';
import { ServerHealthCards } from '../components/ServerHealthCards';
import { ServerResourceCard } from '../components/ServerResourceCard';
import { useSystemMonitoring } from '../hooks/useSystemMonitoring';
import { filterToLatestDay } from '../utils/filterToLatestDay';

const ERROR_LOG_PAGE_SIZE = 10;
// 분석 잡 큐는 요약 카드일 뿐이라 최대 5건만 노출하고, 그 이상 상세 조회는 DB에서 직접 확인하게
// 한다(사용자 지시) — 목록 조회 API·페이지네이션을 별도로 만들지 않는다.
const JOB_QUEUE_DISPLAY_LIMIT = 5;

// 플랫폼 관리자 > 시스템 모니터링(#729) — Figma node-id 1-404. 헤더(브레드크럼)·사이드바는
// PlatformAdminShellRoute가 담당하므로 이 페이지는 CONTENT 영역만 그린다(다른 platform-admin
// 페이지와 동일 계약). 분석 잡 큐는 최신 1일치 중 최대 5건만 노출한다(사용자 지시) — 큐 요약 배지
// (진행/완료/실패)는 별도 집계값이라 이 필터의 영향을 받지 않는다. 에러 로그는 날짜 검색 + LEVEL 필터 + 페이지네이션으로
// 전체 목록(백엔드 최근 50건)을 탐색한다(#729 후속).
export function PlatformAdminMonitoringPage() {
  const { data, isLoading, isError, refetch } = useSystemMonitoring();

  // 기본값은 '전체'(빈 문자열) — 예전엔 '오늘'로 고정했으나(#729 후속), 에러가 드문 정상 운영일엔
  // 항상 빈 화면으로 보여 모니터링 패널의 본래 목적(최근 장애 관측)을 무력화했다(PR #766 리뷰 지적,
  // 브라우저 로컬 타임존과 서버 KST 타임존 불일치로 자정 근처 오탐도 겹침). 날짜는 필요할 때 사용자가
  // 직접 좁혀 쓰는 선택적 필터로 되돌린다.
  const [errorLogDate, setErrorLogDate] = useState('');
  const [errorLogLevel, setErrorLogLevel] = useState<ErrorLogLevelFilter>('ALL');
  const [errorLogPage, setErrorLogPage] = useState(1);

  // 날짜·레벨 조건이 바뀌면 1페이지로 되돌린다(PlatformAdminUsersPage와 동일 패턴).
  const errorLogFilterSignature = `${errorLogDate}|${errorLogLevel}`;
  const prevErrorLogFilterSignatureRef = useRef(errorLogFilterSignature);
  if (prevErrorLogFilterSignatureRef.current !== errorLogFilterSignature) {
    prevErrorLogFilterSignatureRef.current = errorLogFilterSignature;
    setErrorLogPage(1);
  }

  const latestDayJobs = filterToLatestDay(data?.jobQueue.jobs ?? [], (job) => job.recordedAt).slice(
    0,
    JOB_QUEUE_DISPLAY_LIMIT,
  );

  const filteredErrorLogs = (data?.errorLogs ?? []).filter((log) => {
    const matchesDate = !errorLogDate || log.timestamp.startsWith(errorLogDate);
    const matchesLevel = errorLogLevel === 'ALL' || log.level === errorLogLevel;
    return matchesDate && matchesLevel;
  });
  const errorLogTotalPages = Math.max(1, Math.ceil(filteredErrorLogs.length / ERROR_LOG_PAGE_SIZE));
  const pagedErrorLogs = filteredErrorLogs.slice(
    (errorLogPage - 1) * ERROR_LOG_PAGE_SIZE,
    errorLogPage * ERROR_LOG_PAGE_SIZE,
  );

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
        <ServerResourceCard resourceUsage={data?.resourceUsage} isLoading={isLoading} isError={isError} />
      </div>

      <section className="flex flex-col gap-4 rounded-[20px] border border-border bg-surface p-6">
        <h3 className="m-0 text-base font-bold text-heading">에러 로그</h3>
        <ErrorLogFilterBar
          date={errorLogDate}
          onDateChange={setErrorLogDate}
          level={errorLogLevel}
          onLevelChange={setErrorLogLevel}
        />
        <ErrorLogTable
          logs={pagedErrorLogs}
          isLoading={isLoading}
          isError={isError}
          pageSize={ERROR_LOG_PAGE_SIZE}
        />
        <div className="flex justify-end">
          <Pagination
            currentPage={errorLogPage}
            totalPages={errorLogTotalPages}
            onPageChange={setErrorLogPage}
          />
        </div>
      </section>
    </div>
  );
}
