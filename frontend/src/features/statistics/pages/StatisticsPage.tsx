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
import { useFacilityTypeHeatmap } from '../hooks/useFacilityTypeHeatmap';
import { useMonthlyDefectTrend } from '../hooks/useMonthlyDefectTrend';
import { useStatisticsGradeDistribution } from '../hooks/useStatisticsGradeDistribution';
import { useStatisticsSummary } from '../hooks/useStatisticsSummary';
import { exportStatisticsAsPdf } from '../utils/exportStatisticsAsPdf';
import { getMonthsForPeriod } from '../utils/getMonthsForPeriod';

// 통계 대시보드 — HAJA-40, GitHub #27. Figma 시안(node 77-1454)의 스펙 및 스타일을 완벽 적용했다.
// 레이아웃: 헤더 컨트롤(기간/시설물 필터/내보내기 버튼) → KPI 스트립 → [월별 추이 | 유형별 분포] 1:1 2열 →
// [등급별 분포 | 히트맵] 3.5:6.5 비율 2열 → 요약 테이블.
export function StatisticsPage() {
  const [selectedPeriod, setSelectedPeriod] = useState<PeriodOptionValue>('6m');
  const [selectedFacility, setSelectedFacility] = useState<FacilityOptionValue>('all');
  const [isExporting, setIsExporting] = useState(false);
  const [exportError, setExportError] = useState<string | null>(null);

  const filterParams = useMemo(
    () => ({ period: selectedPeriod, facilityId: selectedFacility }),
    [selectedPeriod, selectedFacility],
  );

  const { data: kpiData } = useStatisticsSummary(filterParams);
  const { data: monthlyData } = useMonthlyDefectTrend(filterParams);
  const { data: defectTypeData } = useDefectTypeDistribution(filterParams);
  const { data: gradeData } = useStatisticsGradeDistribution(filterParams);
  const { data: heatmapData } = useFacilityTypeHeatmap(filterParams);
  const { data: facilityData } = useFacilitySummary(); // 전체 시설물 목록용 (필터 무관 전체 목록)

  const facilityOptions: FacilityOptionItem[] = useMemo(() => {
    const list = (facilityData ?? []).map((f) => ({
      id: String(f.facilityId),
      name: f.facilityName,
    }));
    return [{ id: 'all', name: '전체 시설물' }, ...list];
  }, [facilityData]);

  // #1692 — CSV(숫자 나열)와 화면 캡처 이미지(텍스트 0자) 둘 다 이 프로젝트의 정식 문서 기준
  // (exportReportToPdf.ts의 관공서 표준서식 계열)과 맞지 않아 폐기했다. 최종적으로는 화면을
  // 찍지 않고, 이미 조회해 둔 통계 데이터를 exportStatisticsAsPdf가 표 문서로 직접 그린다
  // (폰트 fetch가 비동기라 isExporting으로 중복 클릭을 막고, 실패 시 에러 문구를 보여준다).
  const handleExport = async () => {
    setIsExporting(true);
    setExportError(null);
    try {
      const periodLabel = PERIOD_OPTIONS.find((opt) => opt.value === selectedPeriod)?.label ?? '최근 6개월';
      const facilityLabel = facilityOptions.find((opt) => opt.id === selectedFacility)?.name ?? '전체 시설물';
      const heatmapDataMonths = heatmapData ? [...new Set(heatmapData.map((cell) => cell.month))].sort() : [];

      await exportStatisticsAsPdf({
        periodLabel,
        facilityLabel,
        kpiSummary: kpiData,
        monthlyTrend: monthlyData,
        defectTypeDistribution: defectTypeData,
        gradeDistribution: gradeData,
        facilityTypeHeatmap: heatmapData,
        heatmapMonths: getMonthsForPeriod(selectedPeriod, heatmapDataMonths),
        facilitySummary: facilityData,
      });
    } catch (error) {
      console.error('통계 PDF 내보내기 실패', error);
      setExportError('내보내기에 실패했습니다. 잠시 후 다시 시도해 주세요.');
    } finally {
      setIsExporting(false);
    }
  };

  return (
    <div className="flex min-h-full flex-col gap-5 bg-surface-muted p-6">
      <div className="flex flex-col rounded-[20px] border border-border bg-surface p-8 shadow-sm">
        <div className="flex flex-wrap items-center justify-between gap-4 pb-6 border-b border-border">
          <h1 className="m-0 text-3xl font-semibold text-heading">통계</h1>
          <StatisticsFilterBar
            selectedPeriod={selectedPeriod}
            onPeriodChange={setSelectedPeriod}
            selectedFacility={selectedFacility}
            onFacilityChange={setSelectedFacility}
            facilityOptions={facilityOptions}
            onExport={handleExport}
            isExporting={isExporting}
          />
        </div>

        {exportError && (
          <p role="alert" className="m-0 mt-4 text-sm text-danger">
            {exportError}
          </p>
        )}

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
    </div>
  );
}
