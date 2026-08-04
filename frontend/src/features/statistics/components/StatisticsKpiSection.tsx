import { LoadingSpinner } from '../../../shared/components/LoadingSpinner';
import { useStatisticsSummary } from '../hooks/useStatisticsSummary';
import type { StatisticsFilterParams } from '../types';
import { StatisticsKpiCard } from './StatisticsKpiCard';

interface StatisticsKpiSectionProps {
  filterParams?: StatisticsFilterParams;
}

// PRD §2 KPI 스트립 4종 — Figma 시안(node 77-1454)의 4열 카드 그리드 스타일 반영.
export function StatisticsKpiSection({ filterParams }: StatisticsKpiSectionProps) {
  const { data, isLoading, isError } = useStatisticsSummary(filterParams);

  if (isLoading) {
    return (
      <div className="flex h-32 items-center justify-center bg-white border border-zinc-200 p-6">
        <LoadingSpinner />
      </div>
    );
  }
  if (isError || !data) {
    return (
      <div className="flex h-32 items-center justify-center bg-white border border-zinc-200 p-6">
        <p className="dashboard-card-status m-0 p-0">요약 정보를 불러오지 못했습니다.</p>
      </div>
    );
  }

  const cards = [
    {
      label: '총 탐지 하자',
      value: data.totalDefects.toLocaleString(),
      changeRate: data.totalDefectsChangeRate,
      changeUnit: '%',
    },
    {
      label: '평균 처리일',
      value: `${data.avgResolutionDays}일`,
      changeRate: data.avgResolutionDaysChangeRate,
      changeUnit: '일',
    },
    {
      label: '조치 완료율',
      value: `${data.actionCompletionRate}%`,
      changeRate: data.actionCompletionRateChangeRate,
      changeUnit: '%',
    },
    {
      label: '진행성 하자',
      value: `${data.progressingDefects}`,
      changeRate: data.progressingDefectsChangeRate,
      changeUnit: '',
    },
  ];

  return (
    <div className="grid grid-cols-4 max-[1100px]:grid-cols-2 max-[640px]:grid-cols-1 bg-white border border-zinc-200">
      {cards.map((card, index) => (
        <StatisticsKpiCard key={card.label} {...card} showDivider={index < cards.length - 1} />
      ))}
    </div>
  );
}
