import { useRef, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Button } from '../../../shared/components/Button';
import '../../../shared/styles/layout.css';
import { ComparisonKpiCard } from '../components/ComparisonKpiCard';
import { ComparisonVisualPanel } from '../components/ComparisonVisualPanel';
import { CrackTrendChart } from '../components/CrackTrendChart';
import { DefectChangeTable } from '../components/DefectChangeTable';
import { exportComparisonReportAsPng } from '../utils/exportComparisonReportAsPng';
import { useFacilityComparison } from '../hooks/useFacilityComparison';

const DEFAULT_FACILITY_ID = 'detail';

// 회차 간 비교(dev-04-02, #489) — 하자 상세 화면의 "회차비교" 탭에서 navigate로 진입.
export function FacilityInspectionComparePage() {
  const { id = DEFAULT_FACILITY_ID } = useParams<{ id: string }>();
  const [isExporting, setIsExporting] = useState(false);
  const [exportError, setExportError] = useState<string | null>(null);
  const exportTargetRef = useRef<HTMLDivElement | null>(null);
  // #1157 — before/after를 지정하지 않으면 서버가 이 시설물의 실제 최근 2개 회차로 자동
  // 대체해 응답한다. 회차를 사용자가 직접 고르는 UI는 제공하지 않는다(2026-07-29 사용자
  // 결정) — 항상 서버가 고른 최근 2개 회차만 읽기 전용으로 보여준다.
  const { data, isLoading, isError, refetch } = useFacilityComparison(id);

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
            {/* 회차는 서버가 자동으로 고른 최근 2개로 고정 표시한다(읽기 전용, 2026-07-29
                사용자 결정) — 선택 가능한 드롭다운을 제공하지 않는다.
                code-reviewer P2 — aria-label을 쓰면 접근성 트리에서 자식 텍스트(실제 회차·날짜
                값)가 통째로 가려져 스크린리더 사용자에게 값 자체가 전달되지 않는다. 시각적으로만
                숨긴 라벨 텍스트 뒤에 실제 값을 그대로 두어 라벨·값 둘 다 전달되게 한다. */}
            <span className="rounded-full border border-border bg-surface px-3 py-1.5 text-sm font-semibold text-text-default">
              <span className="sr-only">이전 회차: </span>
              {data.beforeCycle.cycle}회차 {data.beforeCycle.date}
            </span>
            <span className="text-sm font-semibold text-text-muted">VS</span>
            <span className="rounded-full border border-border bg-surface px-3 py-1.5 text-sm font-semibold text-text-default">
              <span className="sr-only">현재 회차: </span>
              {data.afterCycle.cycle}회차 {data.afterCycle.date}
            </span>
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