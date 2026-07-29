import { Fragment, useState } from 'react';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner';
import { Modal } from '../../../shared/components/Modal';
import { useFacilityTypeHeatmap } from '../hooks/useFacilityTypeHeatmap';
import type { StatisticsFilterParams } from '../types';
import { formatMonthLabel } from '../utils/formatMonthLabel';
import { getMonthsForPeriod } from '../utils/getMonthsForPeriod';

// Figma 시안(node 77-1454)의 히트맵은 "지하주차장/외벽/공용부/옥상"처럼 시설물 내부 세부구역을
// 행으로 쓰지만, PRD §3.2 C안으로 팀이 "시설물 유형(건물/교량/도로/기타) × 월별" 축으로 이미
// 확정했고 데이터 계층(FacilityTypeMonthlyHeatmapCell/useFacilityTypeHeatmap)도 그렇게 만들어져
// 있다(수정 금지 대상). 따라서 행 라벨은 Figma 원본이 아니라 이미 확정된 시설물 유형 카테고리를
// 그대로 쓰고, 셀 색상 스케일(5단계 그레이스케일)·범례·레이아웃만 Figma를 따른다.
// 카피("시설물군별 히트맵" 등)도 PRD §3.2에 "확정 전"으로 명시돼 있어 최소 기능 텍스트만 사용한다.
const HEAT_SCALE = ['bg-zinc-100', 'bg-zinc-200', 'bg-zinc-400', 'bg-zinc-600', 'bg-zinc-900'];

// PM 논의 반영(#27): FacilitySummaryTable과 동일 관례 — 행(시설물 유형 카테고리)이 늘어날 수
// 있는 구조이므로 기본 화면엔 상위 VISIBLE_LIMIT행만 보여주고, 초과분은 검정 텍스트 "전체보기"
// 버튼 → 공용 Modal로 뺀다. 현재 카테고리는 4종(건물/교량/도로/기타)으로 고정돼 있어 지금 당장은
// 버튼이 노출되지 않지만, 카테고리가 늘어나는 향후 변경에도 카드가 무한정 커지지 않도록 대비한다.
const VISIBLE_LIMIT = 4;

function getHeatShade(count: number, max: number): string {
  if (count <= 0) return HEAT_SCALE[0]; // 0건은 가장 옅은 백그라운드 색상(Level 0)
  if (max <= 0) return HEAT_SCALE[1];

  const ratio = count / max;
  if (ratio <= 0.25) return HEAT_SCALE[1]; // 데이터가 1건이라도 존재하면 Level 1 이상 적용
  if (ratio <= 0.5) return HEAT_SCALE[2];
  if (ratio <= 0.75) return HEAT_SCALE[3];
  return HEAT_SCALE[4];
}

interface HeatmapGridProps {
  categories: string[];
  months: string[];
  findCount: (category: string, month: string) => number;
  maxCount: number;
}

// 미리보기(상위 N행)와 "전체보기" 모달이 동일한 마크업을 쓰도록 분리.
function HeatmapGrid({ categories, months, findCount, maxCount }: HeatmapGridProps) {
  const isDense = months.length > 8; // 12개월(1년) 선택 시 간격 및 폰트를 조밀하게 맞춰 가로 스크롤 방지
  const headerWidth = isDense ? '2.75rem' : '3.5rem';
  const gapClass = isDense ? 'gap-x-1' : 'gap-x-2';
  const fontSizeClass = isDense ? 'text-[11px]' : 'text-xs';

  return (
    <div className="w-full flex-1 overflow-x-auto overflow-y-visible">
      {/* 카드 폭이 넓어져도(등급별 분포 대비 3.5:6.5) 셀이 좌상단에 뭉치지 않도록, 고정폭
          flex 대신 월 열을 1fr 그리드 컬럼으로 잡아 카드 전체 폭에 맞춰 늘어나게 한다. */}
      <div
        className={`grid w-full items-center ${gapClass} gap-y-2 min-w-[360px]`}
        style={{ gridTemplateColumns: `${headerWidth} repeat(${months.length}, 1fr)` }}
      >
        <span aria-hidden="true" />
        {months.map((month) => (
          <span key={month} className={`text-center text-zinc-500 font-medium ${fontSizeClass}`}>
            {formatMonthLabel(month)}
          </span>
        ))}
        {categories.map((category, rowIndex) => {
          // 부모 컨테이너(overflow-x-auto)는 overflow-y도 암묵적으로 auto가 되어(CSS 스펙상
          // 두 축 중 하나만 visible이 아니면 나머지도 auto로 강제됨) 위로 벗어나는 절대배치
          // 엘리먼트를 잘라낸다. 맨 윗줄만 툴팁을 셀 아래쪽에 띄워 위쪽 클리핑을 피한다.
          const isFirstRow = rowIndex === 0;
          return (
            <Fragment key={category}>
              <span className={`text-right text-zinc-500 font-medium truncate ${fontSizeClass}`}>{category}</span>
              {months.map((month, colIndex) => {
                const count = findCount(category, month);
                const isFirstCol = colIndex === 0;
                const isLastCol = colIndex === months.length - 1;

                const verticalPosClass = isFirstRow ? 'top-full mt-1.5' : 'bottom-full mb-1.5';
                const horizontalPosClass = isLastCol
                  ? 'right-0 translate-x-0'
                  : isFirstCol
                  ? 'left-0 translate-x-0'
                  : 'left-1/2 -translate-x-1/2';

                return (
                  <span key={month} className="group relative">
                    <span
                      aria-label={`${category} · ${formatMonthLabel(month)} · ${count}건`}
                      className={`block h-10 w-full rounded-xs ${getHeatShade(count, maxCount)}`}
                    />
                    {/* 상하좌우 경계선 안쪽으로 칩이 팝업되도록 4방향 앵커링 조율 (가로 스크롤바 방지) */}
                    <span
                      role="tooltip"
                      className={`pointer-events-none absolute z-10 hidden whitespace-nowrap rounded-[10px] bg-white px-3.5 py-2 text-[13px] font-semibold text-zinc-800 shadow-[0_8px_24px_rgba(0,0,0,0.25)] group-hover:block ${verticalPosClass} ${horizontalPosClass}`}
                    >
                      {category} · {formatMonthLabel(month)} · {count}건
                    </span>
                  </span>
                );
              })}
            </Fragment>
          );
        })}
      </div>
    </div>
  );
}

interface FacilityTypeHeatmapProps {
  filterParams?: StatisticsFilterParams;
}

export function FacilityTypeHeatmap({ filterParams }: FacilityTypeHeatmapProps) {
  const { data, isLoading, isError } = useFacilityTypeHeatmap(filterParams);
  const [isModalOpen, setIsModalOpen] = useState(false);

  const rawCategories = data ? [...new Set(data.map((cell) => cell.facilityTypeCategory))] : [];
  const categories = rawCategories.length > 0 ? rawCategories : ['건물', '교량', '도로', '기타'];

  const dataMonths = data ? [...new Set(data.map((cell) => cell.month))].sort() : [];
  const months = getMonthsForPeriod(filterParams?.period, dataMonths);
  const maxCount = data && data.length > 0 ? Math.max(...data.map((cell) => cell.defectCount)) : 0;
  const hasMore = categories.length > VISIBLE_LIMIT;
  const visibleCategories = categories.slice(0, VISIBLE_LIMIT);

  const findCount = (category: string, month: string) =>
    data?.find((cell) => cell.facilityTypeCategory === category && cell.month === month)?.defectCount ?? 0;

  return (
    <section className="flex h-full min-h-[320px] flex-col bg-white border border-zinc-200 p-6">
      <div className="mb-6 flex items-center justify-between">
        <h3 className="text-zinc-900 text-base font-medium leading-6">시설물군별 히트맵</h3>
        <div className="flex items-center gap-3">
          <div className="flex items-center gap-1">
            <span className="text-zinc-400 text-xs">Low</span>
            {HEAT_SCALE.map((shade) => (
              <span key={shade} className={`size-3 rounded-xs ${shade}`} />
            ))}
            <span className="text-zinc-400 text-xs">High</span>
          </div>
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
      </div>
      {isLoading && <LoadingSpinner />}
      {isError && <p className="dashboard-card-status">히트맵 데이터를 불러오지 못했습니다.</p>}
      {!isLoading && !isError && categories.length === 0 && (
        <p className="dashboard-card-status">표시할 데이터가 없습니다.</p>
      )}
      {!isLoading && !isError && categories.length > 0 && (
        <HeatmapGrid categories={visibleCategories} months={months} findCount={findCount} maxCount={maxCount} />
      )}
      {hasMore && (
        <Modal open={isModalOpen} onClose={() => setIsModalOpen(false)} title="시설물군별 히트맵 전체보기">
          <div className="max-h-[70vh] w-[min(90vw,900px)] overflow-y-auto">
            <HeatmapGrid categories={categories} months={months} findCount={findCount} maxCount={maxCount} />
          </div>
        </Modal>
      )}
    </section>
  );
}
