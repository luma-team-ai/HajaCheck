import { useState } from 'react';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner';
import { Modal } from '../../../shared/components/Modal';
import { useFacilitySummary } from '../hooks/useFacilitySummary';
import type { FacilitySummaryItem, StatisticsFilterParams } from '../types';

interface FacilitySummaryTableProps {
  filterParams?: StatisticsFilterParams;
}

// PM 논의 반영(#27): 시설물이 많은 회사는 행이 무한정 길어질 수 있어 기본 화면엔 상위
// VISIBLE_LIMIT건만 보여주고, 초과분은 "전체보기" 버튼(검정 텍스트 — Figma 스펙, dashboard-card-link의
// 파란색과는 다름) → 공용 Modal(shared/components/Modal, RecentInspectionsTable "전체보기"와 동일
// 열림/닫힘 UI 관례)로 뺀다.
const VISIBLE_LIMIT = 6;

interface FacilitySummaryRowsTableProps {
  items: FacilitySummaryItem[];
  maxDefects: number;
}

// 미리보기(상위 N건)와 "전체보기" 모달이 동일한 마크업을 쓰도록 분리 — maxDefects는 항상 전체
// 데이터셋 기준으로 계산해 전달한다(미리보기만 봤을 때 막대 비율이 달라지지 않도록).
function FacilitySummaryRowsTable({ items, maxDefects }: FacilitySummaryRowsTableProps) {
  return (
    <div className="overflow-x-auto pb-4">
      <table className="w-full min-w-[600px] border-collapse">
        <thead>
          <tr className="border-b border-zinc-200">
            <th className="w-96 px-6 py-3 text-left text-zinc-400 text-xs font-medium uppercase tracking-wide">
              시설물
            </th>
            <th className="w-48 px-6 py-3 text-left text-zinc-400 text-xs font-medium uppercase tracking-wide">
              총 수량
            </th>
            <th className="w-60 px-6 py-3 text-left text-zinc-400 text-xs font-medium uppercase tracking-wide">
              최근 점검일
            </th>
            <th className="w-36 px-6 py-3 text-left text-zinc-400 text-xs font-medium uppercase tracking-wide">
              최근 등급
            </th>
          </tr>
        </thead>
        <tbody>
          {items.map((item) => (
            <tr
              key={item.facilityId}
              className="group border-b border-zinc-100 last:border-b-0 hover:bg-surface-muted"
            >
              <td className="px-6 py-3 text-zinc-900 text-xs font-medium">{item.facilityName}</td>
              <td className="px-6 py-3">
                <div className="flex items-center gap-2">
                  <div className="h-1.5 w-24 overflow-hidden rounded-full bg-zinc-200">
                    <div
                      className="h-1.5 rounded-full bg-zinc-600 group-hover:bg-primary"
                      style={{
                        width: `${maxDefects > 0 ? (item.totalDefects / maxDefects) * 100 : 0}%`,
                      }}
                    />
                  </div>
                  <span className="text-zinc-500 text-xs group-hover:text-primary">{item.totalDefects}</span>
                </div>
              </td>
              <td className="px-6 py-3 text-zinc-500 text-xs">{item.lastInspectedAt}</td>
              <td className="px-6 py-3 text-zinc-500 text-xs">{item.latestGrade ?? '-'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function FacilitySummaryTable({ filterParams }: FacilitySummaryTableProps) {
  const { data, isLoading, isError } = useFacilitySummary(filterParams);
  const [isModalOpen, setIsModalOpen] = useState(false);
  const maxDefects = data && data.length > 0 ? Math.max(...data.map((item) => item.totalDefects)) : 0;
  const hasMore = !!data && data.length > VISIBLE_LIMIT;
  const visibleData = data ? data.slice(0, VISIBLE_LIMIT) : [];

  return (
    <section className="flex min-h-[220px] flex-col overflow-hidden bg-white border border-zinc-200 p-6">
      <div className="flex items-center justify-between pb-4">
        <h3 className="text-zinc-900 text-base font-medium leading-6">시설물별 요약</h3>
        {hasMore && (
          <button
            type="button"
            className="cursor-pointer border-none bg-transparent text-[13px] font-semibold text-zinc-900 hover:underline"
            onClick={() => setIsModalOpen(true)}
          >
            전체보기
          </button>
        )}
      </div>
      {isLoading && (
        <div className="my-auto flex flex-1 items-center justify-center py-8">
          <LoadingSpinner />
        </div>
      )}
      {isError && (
        <div className="my-auto flex flex-1 items-center justify-center py-8">
          <p className="dashboard-card-status m-0 p-0">시설물별 요약을 불러오지 못했습니다.</p>
        </div>
      )}
      {!isLoading && !isError && (!data || data.length === 0) && (
        <div className="my-auto flex flex-1 items-center justify-center py-8">
          <p className="dashboard-card-status m-0 p-0">등록된 시설물이 없습니다.</p>
        </div>
      )}
      {!isLoading && !isError && data && data.length > 0 && (
        <FacilitySummaryRowsTable items={visibleData} maxDefects={maxDefects} />
      )}
      {hasMore && (
        <Modal open={isModalOpen} onClose={() => setIsModalOpen(false)} title="시설물별 요약 전체보기">
          <div className="max-h-[70vh] w-[min(90vw,800px)] overflow-y-auto">
            <FacilitySummaryRowsTable items={data ?? []} maxDefects={maxDefects} />
          </div>
        </Modal>
      )}
    </section>
  );
}
