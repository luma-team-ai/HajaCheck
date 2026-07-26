import type { ReportGrade, ReportGradeDistribution } from '../types';

const GRADE_ORDER: ReportGrade[] = ['A', 'B', 'C', 'D', 'E'];

// 공용 디자인 토큰 및 피그마 캡처본(308% 줌) 1:1 연동 색상 체계 (A=빨강, B=주황, C=노랑, D=초록, E=에메랄드)
const GRADE_CLASSES: Record<ReportGrade, string> = {
  A: 'bg-red-50 text-red-600',
  B: 'bg-orange-50 text-orange-600',
  C: 'bg-yellow-50 text-amber-700',
  D: 'bg-green-50 text-green-600',
  E: 'bg-emerald-50 text-emerald-600',
};

type Props = {
  distribution: ReportGradeDistribution;
};

// 보고서 목록 테이블 "하자 요약" 컬럼 — 등급별 배지 (피그마 시안 1:1 무외곽선 알약 배지)
export function ReportGradeBadges({ distribution }: Props) {
  const entries = GRADE_ORDER.map((grade) => [grade, distribution[grade] ?? 0] as const).filter(
    ([, count]) => count > 0,
  );

  if (entries.length === 0) {
    return <span className="text-xs text-text-muted">-</span>;
  }

  return (
    <span className="inline-flex flex-wrap items-center gap-1">
      {entries.map(([grade, count]) => (
        <span
          key={grade}
          className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-[11px] font-bold ${GRADE_CLASSES[grade]}`}
          title={`${grade}등급 ${count}건`}
        >
          {grade} {count}
        </span>
      ))}
    </span>
  );
}
