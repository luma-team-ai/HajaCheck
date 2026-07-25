import { useMemo, useState } from 'react';
import '../../../shared/styles/layout.css';
import { DefectTypeDistributionCard } from '../components/DefectTypeDistributionCard';
import { FacilitySummaryTable } from '../components/FacilitySummaryTable';
import { FacilityTypeHeatmap } from '../components/FacilityTypeHeatmap';
import { MonthlyTrendCard } from '../components/MonthlyTrendCard';
import { StatisticsGradeDistributionCard } from '../components/StatisticsGradeDistributionCard';
import { StatisticsKpiSection } from '../components/StatisticsKpiSection';
import {
  PERIOD_OPTIONS,
  StatisticsFilterBar,
  type FacilityOptionItem,
  type FacilityOptionValue,
  type PeriodOptionValue,
} from '../components/StatisticsFilterBar';
import { useDefectTypeDistribution } from '../hooks/useDefectTypeDistribution';
import { useFacilitySummary } from '../hooks/useFacilitySummary';
import { useMonthlyDefectTrend } from '../hooks/useMonthlyDefectTrend';
import { useStatisticsGradeDistribution } from '../hooks/useStatisticsGradeDistribution';
import { useStatisticsSummary } from '../hooks/useStatisticsSummary';
import { exportStatisticsToCsv } from '../utils/exportStatisticsCsv';

// 통계 대시보드 — HAJA-40, GitHub #27. Figma 시안(node 77-1454)의 스펙 및 스타일을 완벽 적용했다.
// 레이아웃: 헤더 컨트롤(기간/시설물 필터/내보내기 버튼) → KPI 스트립 → [월별 추이 | 유형별 분포] 1:1 2열 →
// [등급별 분포 | 히트맵] 3.5:6.5 비율 2열 → 요약 테이블.
export function StatisticsPage() {
  const [selectedPeriod, setSelectedPeriod] = useState<PeriodOptionValue>('6m');
  const [selectedFacility, setSelectedFacility] = useState<FacilityOptionValue>('all');

  const filterParams = useMemo(
    () => ({ period: selectedPeriod, facilityId: selectedFacility }),
    [selectedPeriod, selectedFacility],
  );

  const { data: kpiData } = useStatisticsSummary(filterParams);
  const { data: monthlyData } = useMonthlyDefectTrend(filterParams);
  const { data: defectTypeData } = useDefectTypeDistribution(filterParams);
  const { data: gradeData } = useStatisticsGradeDistribution(filterParams);
  const { data: facilityData } = useFacilitySummary(); // 전체 시설물 목록용 (필터 무관 전체 목록)

  const facilityOptions: FacilityOptionItem[] = useMemo(() => {
    const list = (facilityData ?? []).map((f) => ({
      id: String(f.facilityId),
      name: f.facilityName,
    }));
    return [{ id: 'all', name: '전체 시설물' }, ...list];
  }, [facilityData]);

  const handleExport = () => {
    const periodLabel = PERIOD_OPTIONS.find((opt) => opt.value === selectedPeriod)?.label ?? '최근 6개월';
    const facilityLabel =
      facilityOptions.find((opt) => opt.id === selectedFacility)?.name ?? '전체 시설물';

    exportStatisticsToCsv({
      periodLabel,
      facilityLabel,
      kpiSummary: kpiData,
      monthlyTrend: monthlyData,
      defectTypeDistribution: defectTypeData,
      gradeDistribution: gradeData,
      facilitySummary: facilityData,
    });
  };

  return (
    <div className="dashboard-content rounded-none!">
      <div className="dashboard-page-header flex flex-wrap items-center justify-between gap-4 pb-4! border-b border-zinc-200">
        <h1 className="dashboard-page-title text-3xl! font-medium! text-zinc-900! leading-10!">통계</h1>
        <StatisticsFilterBar
          selectedPeriod={selectedPeriod}
          onPeriodChange={setSelectedPeriod}
          selectedFacility={selectedFacility}
          onFacilityChange={setSelectedFacility}
          facilityOptions={facilityOptions}
          onExport={handleExport}
        />
      </div>

      <div className="mt-6 flex flex-col gap-6">
        <StatisticsKpiSection filterParams={filterParams} />
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          <MonthlyTrendCard filterParams={filterParams} />
          <DefectTypeDistributionCard filterParams={filterParams} />
        </div>
        <div className="grid grid-cols-1 lg:grid-cols-[3.5fr_6.5fr] gap-6">
          <StatisticsGradeDistributionCard filterParams={filterParams} />
          <FacilityTypeHeatmap filterParams={filterParams} />
        </div>
        <FacilitySummaryTable filterParams={filterParams} />
      </div>
    </div>
  );
}
