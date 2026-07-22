import { formatComparisonChange, getComparisonChangeColorClass } from '../utils/formatComparisonChange';
import type { ComparisonKpi } from '../types';

type Props = {
  kpi: ComparisonKpi;
};

// dashboard/components/KpiCard.tsx와 동일한 타이포 스케일(28px/extrabold 값, 13px/semibold 라벨,
// 13px/bold 증감 배지)을 따른다 — feature 간 직접 import 금지(§1)라 클래스만 로컬로 재사용한다.
// 색상 규칙은 대시보드와 달리 "양수=악화(빨강), 음수=개선(초록)"(#489 스펙, formatComparisonChange.ts).
export function ComparisonKpiCard({ kpi }: Props) {
  return (
    <div className="dashboard-card flex flex-col gap-2.5">
      <span className="text-[13px] font-semibold text-[#888]">{kpi.label}</span>
      <p className="m-0 flex items-baseline gap-2">
        <span className="text-[28px] font-extrabold leading-none text-heading">{kpi.value}</span>
        <span className={`text-[13px] font-bold ${getComparisonChangeColorClass(kpi.changeValue)}`}>
          {formatComparisonChange(kpi.changeValue)}
        </span>
      </p>
    </div>
  );
}