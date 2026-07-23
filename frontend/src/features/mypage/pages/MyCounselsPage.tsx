import { useState } from 'react';
import type { CategoryFilterValue } from '../components/CategoryFilterSelect';
import { CategoryFilterSelect } from '../components/CategoryFilterSelect';
import { MyCounselsTable } from '../components/MyCounselsTable';
import type { PeriodFilterValue } from '../components/PeriodFilterSelect';
import { PeriodFilterSelect } from '../components/PeriodFilterSelect';
import { useMyCounsels } from '../hooks/useMyCounsels';

const DEFAULT_PAGE_SIZE = 8; // MyInspectionsPage와 동일 관례 — "1-8 / 18" 페이지네이션 표기에 맞춘 기본값

// 마이페이지 — 내 상담 내역 (HAJA-371, #678 / Figma 시안). MyInspectionsPage(HAJA-366, #668)와
// 완전히 동일한 패턴(MSW mock + fetchWithFallback + react-query 훅 + 프론트 전용 타입)을 따른다.
// 상담(counsel) BE API가 전무하다 — backend의 counsel 관련 controller/service/repository는
// .gitkeep만 있는 빈 스켈레톤이고 엔티티만 존재한다 — 전 구간 mock으로 렌더한다. 카테고리/기간
// 필터는 로컬 state로만 존재하고 조회 파라미터에 연결하지 않는다(후속 BE 연동 시 실 쿼리 파라미터로 승격).
export function MyCounselsPage() {
  const [category, setCategory] = useState<CategoryFilterValue>('ALL');
  const [period, setPeriod] = useState<PeriodFilterValue>('3M');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);

  const counselsQuery = useMyCounsels({ page, size: pageSize });

  const rows = counselsQuery.data?.content ?? [];
  const totalItems = counselsQuery.data?.totalElements ?? 0;

  function handlePageSizeChange(size: number) {
    setPageSize(size);
    setPage(1); // 페이지 크기가 바뀌면 1페이지로 되돌린다(MyInspectionsPage와 동일 관례)
  }

  return (
    <div className="flex min-h-full flex-col gap-6 bg-surface-muted p-6 sm:p-8">
      <div className="mx-auto flex w-full max-w-6xl flex-col gap-6 rounded-[20px] border border-border bg-white p-8 shadow-sm">
        <div className="flex flex-wrap items-center justify-between gap-4">
          <h1 className="m-0 text-2xl font-bold text-heading">내 상담 내역</h1>
          <div className="flex flex-wrap items-center gap-3">
            <span className="text-sm text-text-muted">총 {totalItems}건</span>
            <CategoryFilterSelect value={category} onChange={setCategory} />
            <PeriodFilterSelect value={period} onChange={setPeriod} />
          </div>
        </div>

        <MyCounselsTable
          rows={rows}
          isLoading={counselsQuery.isLoading}
          isError={counselsQuery.isError}
          currentPage={page}
          pageSize={pageSize}
          totalItems={totalItems}
          onPageChange={setPage}
          onPageSizeChange={handlePageSizeChange}
        />
      </div>
    </div>
  );
}
