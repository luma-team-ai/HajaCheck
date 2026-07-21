import { useRef, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Button } from '../../../shared/components/Button';
import '../../../shared/styles/layout.css';
import { ComparisonKpiCard } from '../components/ComparisonKpiCard';
import { ComparisonVisualPanel } from '../components/ComparisonVisualPanel';
import { CrackTrendChart } from '../components/CrackTrendChart';
import { DefectChangeTable } from '../components/DefectChangeTable';
import { InspectionCycleSelect } from '../components/InspectionCycleSelect';
import { exportComparisonReportAsPng } from '../utils/exportComparisonReportAsPng';
import { useFacilityComparison } from '../hooks/useFacilityComparison';

const DEFAULT_FACILITY_ID = 'detail';
const DEFAULT_BEFORE_CYCLE = 7;
const DEFAULT_AFTER_CYCLE = 8;

// 회차 간 비교(dev-04-02, #489) — 하자 상세 화면의 "회차비교" 탭에서 navigate로 진입.
export function FacilityInspectionComparePage() {
  const { id = DEFAULT_FACILITY_ID } = useParams<{ id: string }>();
  const [beforeCycle, setBeforeCycle] = useState(DEFAULT_BEFORE_CYCLE);
  const [afterCycle, setAfterCycle] = useState(DEFAULT_AFTER_CYCLE);
  const [isExporting, setIsExporting] = useState(false);
  const [exportError, setExportError] = useState<string | null>(null);
  const exportTargetRef = useRef<HTMLDivElement | null>(null);
  const { data, isLoading, isError, refetch } = useFacilityComparison(id, beforeCycle, afterCycle);

  const handleExportClick = async () => {
    if (!exportTargetRef.current) return;
    setIsExporting(true);
    setExportError(null);
    try {
      await exportComparisonReportAsPng(exportTargetRef.current, id);
    } catch {
      setExportError('내보내기에 실패했습니다. 잠시 후 다시 시도해 주세요.');
    } finally {
      setIsExporting(false);
    }
  };

  if (isLoading) {
    return <div className="dashboard-content text-sm text-text-muted">불러오는 중...</div>;
  }

  if (isError || !data) {
    return (
      <div className="dashboard-content">
        <p className="m-0 text-sm text-text-muted">회차 비교 정보를 불러오지 못했습니다.</p>
        <button
          type="button"
          onClick={() => refetch()}
          className="self-start text-sm font-semibold text-accent"
        >
          다시 시도
        </button>
      </div>
    );
  }

  return (
    // 사이드바·헤더는 AppLayout(shared)이 별도로 렌더링하므로, 이 콘텐츠 영역만 캡처하면
    // "메인 콘텐츠 영역만" PNG로 내보내는 요구사항이 자연히 충족된다(#489 확정).
    <div className="dashboard-content" ref={exportTargetRef}>
      <div className="dashboard-page-header">
        <div className="flex flex-col gap-3">
          <h1 className="dashboard-page-title">회차 간 비교</h1>
          <div className="flex items-center gap-3">
            <InspectionCycleSelect
              label="이전 회차"
              options={data.availableCycles}
              value={beforeCycle}
              onChange={setBeforeCycle}
            />
            <span className="text-sm font-semibold text-text-muted">VS</span>
            <InspectionCycleSelect
              label="현재 회차"
              options={data.availableCycles}
              value={afterCycle}
              onChange={setAfterCycle}
            />
          </div>
        </div>
        <Button variant="secondary" size="sm" onClick={handleExportClick} disabled={isExporting}>
          {isExporting ? '내보내는 중...' : '내보내기'}
        </Button>
      </div>

      {exportError && (
        <p role="alert" className="m-0 text-sm text-danger">
          {exportError}
        </p>
      )}

      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        {data.kpis.map((kpi) => (
          <ComparisonKpiCard key={kpi.key} kpi={kpi} />
        ))}
      </div>

      <div className="grid gap-6 lg:grid-cols-[1.5fr_1fr]">
        <ComparisonVisualPanel
          beforeCycle={data.beforeCycle}
          afterCycle={data.afterCycle}
          beforeImageUrl={data.beforeImageUrl}
          afterImageUrl={data.afterImageUrl}
        />
        <div className="flex flex-col gap-3">
          <h2 className="m-0 text-base font-bold text-heading">진행성 균열 추이</h2>
          <CrackTrendChart data={data.crackTrend} />
        </div>
      </div>

      <div className="flex flex-col gap-3">
        <h2 className="m-0 text-base font-bold text-heading">하자 변화 목록</h2>
        <div className="overflow-hidden rounded-2xl border border-border">
          <DefectChangeTable
            rows={data.changes}
            beforeCycle={data.beforeCycle.cycle}
            afterCycle={data.afterCycle.cycle}
          />
        </div>
      </div>
    </div>
  );
}