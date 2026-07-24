import { useMemo } from 'react';
import type { Defect } from '../types';

type Props = {
  defects: Defect[];
};

// 점검 상세(카드형, HAJA-393/394 §화면 구조 ②) KPI 4종 — contract.md 확정: 총 하자/검수확정/조치중/조치완료.
export function InspectionKpiSummary({ defects }: Props) {
  const summary = useMemo(
    () => ({
      total: defects.length,
      confirmed: defects.filter((defect) => defect.status === 'CONFIRMED').length,
      inProgress: defects.filter((defect) => defect.status === 'IN_PROGRESS').length,
      resolved: defects.filter((defect) => defect.status === 'RESOLVED').length,
    }),
    [defects],
  );

  const items: { key: string; label: string; value: number }[] = [
    { key: 'total', label: '총 하자', value: summary.total },
    { key: 'confirmed', label: '검수확정', value: summary.confirmed },
    { key: 'inProgress', label: '조치중', value: summary.inProgress },
    { key: 'resolved', label: '조치완료', value: summary.resolved },
  ];

  return (
    <dl className="inspection-kpi-summary" aria-label="점검 하자 요약">
      {items.map((item) => (
        <div className="inspection-kpi-summary__card" key={item.key}>
          <dt>{item.label}</dt>
          <dd>{item.value.toLocaleString()}</dd>
        </div>
      ))}
    </dl>
  );
}
