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

// 회차 간 비교(dev-04-02, #489) — 하자 상세 화면의 "회차비교" 탭에서 navigate로 진입.
export function FacilityInspectionComparePage() {
  const { id = DEFAULT_FACILITY_ID } = useParams<{ id: string }>();
  // #1157 — 시설물마다 실제 점검 회차가 다르므로 화면 진입 시 유효한 회차를 미리 알 수 없다
  // (과거엔 7/8회차로 하드코딩해 그 회차가 없는 시설물에서 항상 실패했다). undefined면 서버가
  // 이 시설물의 실제 최근 2개 회차로 자동 대체해 응답하고, 그 값을 select 표시에 그대로 반영한다.
  const [beforeCycle, setBeforeCycle] = useState<number | undefined>(undefined);
  const [afterCycle, setAfterCycle] = useState<number | undefined>(undefined);
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

  // 서버가 자동 대체한 회차를 select 표시값으로 사용한다(#1157) — 사용자가 아직 명시적으로
  // 고르지 않았으면(undefined) 응답에 실린 beforeCycle/afterCycle이 곧 서버가 고른 실제 값이다.
  const displayedBeforeCycle = beforeCycle ?? data.beforeCycle.cycle;
  const displayedAfterCycle = afterCycle ?? data.afterCycle.cycle;

  // code-reviewer P1 — 한쪽 회차만 바꾸고 다른 쪽을 undefined로 남겨두면, 재요청 시 서버가
  // "둘 다 생략" 경로로 오인해 방금 고른 값까지 자동 대체로 덮어써 버린다(select엔 사용자가
  // 고른 값이 남아 있는데 실제 비교 데이터는 서버가 다시 고른 값이 되는 불일치). 한쪽을 바꿀 때
  // 다른 쪽도 현재 표시값으로 함께 명시해, 사용자가 한 번이라도 선택한 뒤로는 두 값이 항상
  // 같이 정의되도록 한다.
  const handleBeforeCycleChange = (cycle: number) => {
    setBeforeCycle(cycle);
    setAfterCycle(displayedAfterCycle);
  };
  const handleAfterCycleChange = (cycle: number) => {
    setAfterCycle(cycle);
    setBeforeCycle(displayedBeforeCycle);
  };

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
              value={displayedBeforeCycle}
              onChange={handleBeforeCycleChange}
            />
            <span className="text-sm font-semibold text-text-muted">VS</span>
            <InspectionCycleSelect
              label="현재 회차"
              options={data.availableCycles}
              value={displayedAfterCycle}
              onChange={handleAfterCycleChange}
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
          {/* 실 백엔드(HAJA-531/#1112)는 crackTrend를 응답에서 생략한다(null/undefined) — LineChart가
              data.length에 바로 접근해 크래시하므로 빈 배열로 방어한다(react-reviewer 발견, 즉시 수정). */}
          <CrackTrendChart data={data.crackTrend ?? []} />
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