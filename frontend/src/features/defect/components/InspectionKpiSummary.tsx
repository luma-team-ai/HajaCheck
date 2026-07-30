import { useMemo } from 'react';
import type { Defect, DefectStatus } from '../types';
import { STATUS_PRESENTATION } from '../constants/defectPresentation';

type Props = {
  defects: Defect[];
};

// DefectCard.tsx와 동일 패턴(신규 색상 상수 추가 금지 컨벤션) — STATUS_PRESENTATION의
// "border-* bg-* text-*" 필(pill) 배지 클래스에서 text-* 색상 클래스만 뽑아 dot의
// background-color: currentColor로 재사용한다.
function pickTextColorClass(classNames: string): string {
  return classNames.split(' ').find((cls) => cls.startsWith('text-')) ?? '';
}

// 점검 상세(카드형, HAJA-393/394 §화면 구조 ②) KPI 4종 — contract.md 확정: 총 하자/검수확정/조치중/조치완료.
// Figma 정렬(#966) — "총 하자"를 제외한 상태 3종엔 defectPresentation.STATUS_PRESENTATION 색상 dot을 붙인다
// (#937/PR#950 diff에서 이 컴포넌트만 누락됐던 스타일링 보완). "건" 단위는 #969 Header Panel 정렬로
// 4종 전체에 표기하도록 변경(과거엔 "총 하자"만 단위 없이 표시했으나 레퍼런스 디자인과 통일).
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

  const items: { key: string; label: string; value: number; statusKey?: DefectStatus }[] = [
    { key: 'total', label: '총 하자', value: summary.total },
    { key: 'confirmed', label: '검수확정', value: summary.confirmed, statusKey: 'CONFIRMED' },
    { key: 'inProgress', label: '조치중', value: summary.inProgress, statusKey: 'IN_PROGRESS' },
    { key: 'resolved', label: '조치완료', value: summary.resolved, statusKey: 'RESOLVED' },
  ];

  return (
    <dl className="inspection-kpi-summary" aria-label="점검 하자 요약">
      {items.map((item) => {
        const dotColorClass = item.statusKey
          ? pickTextColorClass(STATUS_PRESENTATION[item.statusKey].className)
          : '';

        return (
          <div className="inspection-kpi-summary__card" key={item.key}>
            <dt>
              {item.statusKey && (
                <span
                  className={`inspection-kpi-summary__dot ${dotColorClass}`}
                  aria-hidden="true"
                />
              )}
              {item.label}
            </dt>
            <dd>{item.value.toLocaleString()}건</dd>
          </div>
        );
      })}
    </dl>
  );
}
