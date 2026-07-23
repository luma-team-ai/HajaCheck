import { useState } from 'react';
import { MyInspectionsKpiSection } from '../components/MyInspectionsKpiSection';
import { MyInspectionsTable } from '../components/MyInspectionsTable';
import type { MyInspectionsTab } from '../components/MyInspectionsTabs';
import { MyInspectionsTabs } from '../components/MyInspectionsTabs';
import { MyReportsList } from '../components/MyReportsList';
import type { PeriodFilterValue } from '../components/PeriodFilterSelect';
import { PeriodFilterSelect } from '../components/PeriodFilterSelect';
import { useMyInspections } from '../hooks/useMyInspections';
import { useMyInspectionsSummary } from '../hooks/useMyInspectionsSummary';
import { useMyReports } from '../hooks/useMyReports';

const DEFAULT_PAGE_SIZE = 8; // Figma 시안 "1-8 / 18" 페이지네이션 표기와 맞춘 기본값

// 마이페이지 — 내 점검 이력 / 보고서 (HAJA-366, #668 / Figma 시안). MyPlanPage(#212)/MyProfilePage(#659)와
// 동일하게 흰 카드 하나 안에 섹션을 세로로 구성한다. 다만 이 화면은 기존 divide-y 셸 대신 탭으로
// 콘텐츠 영역을 전환한다 — 헤더(제목·부제·기간 필터)와 KPI 4종은 두 탭 모두에 공통으로 걸리는
// 요약 정보라 항상 노출하고, 탭에 따라 아래 콘텐츠만 바뀐다:
//  - '점검 이력' 탭: 점검 이력 테이블(+페이지네이션)
//  - '내 보고서' 탭: '최근 발급된 보고서' 카드 목록(회색 박스)
// BE API가 전혀 없어(grep 0건) 전 구간 mock + fetchWithFallback로 렌더한다. 기간 필터는 로컬
// state로만 존재하고 조회 파라미터에 연결하지 않는다(후속 BE 연동 시 실 쿼리 파라미터로 승격).
export function MyInspectionsPage() {
  const [activeTab, setActiveTab] = useState<MyInspectionsTab>('HISTORY');
  const [period, setPeriod] = useState<PeriodFilterValue>('6M');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);

  const summaryQuery = useMyInspectionsSummary();
  const inspectionsQuery = useMyInspections({ page, size: pageSize });
  const reportsQuery = useMyReports();

  const rows = inspectionsQuery.data?.content ?? [];
  const totalItems = inspectionsQuery.data?.totalElements ?? 0;

  function handlePageSizeChange(size: number) {
    setPageSize(size);
    setPage(1); // 페이지 크기가 바뀌면 1페이지로 되돌린다(AdminUsersPage와 동일 관례)
  }

  return (
    <div className="flex min-h-full flex-col gap-6 bg-surface-muted p-6 sm:p-8">
      <div className="mx-auto flex w-full max-w-6xl flex-col gap-6 rounded-[20px] border border-border bg-white p-8 shadow-sm">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div>
            <h1 className="m-0 text-2xl font-bold text-heading">내 점검 이력 / 보고서</h1>
            <p className="m-0 mt-1 text-sm text-text-muted">
              내가 참여한 점검 회차와 발급한 보고서를 확인하세요.
            </p>
          </div>
          <PeriodFilterSelect value={period} onChange={setPeriod} />
        </div>

        <MyInspectionsTabs activeTab={activeTab} onChange={setActiveTab} />

        <MyInspectionsKpiSection
          summary={summaryQuery.data}
          isLoading={summaryQuery.isLoading}
          isError={summaryQuery.isError}
        />

        {activeTab === 'HISTORY' && (
          <MyInspectionsTable
            rows={rows}
            isLoading={inspectionsQuery.isLoading}
            isError={inspectionsQuery.isError}
            currentPage={page}
            pageSize={pageSize}
            totalItems={totalItems}
            onPageChange={setPage}
            onPageSizeChange={handlePageSizeChange}
          />
        )}

        {activeTab === 'REPORTS' && (
          <MyReportsList
            reports={reportsQuery.data ?? []}
            isLoading={reportsQuery.isLoading}
            isError={reportsQuery.isError}
          />
        )}
      </div>
    </div>
  );
}
