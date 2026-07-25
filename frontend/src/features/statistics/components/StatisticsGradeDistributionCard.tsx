import { DistributionBar } from '../../../shared/components/charts/DistributionBar';
import { CHART_GRADE_COLORS } from '../../../shared/components/charts/palette';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner';
import { useStatisticsGradeDistribution } from '../hooks/useStatisticsGradeDistribution';
import type { DefectGrade, StatisticsFilterParams } from '../types';

interface StatisticsGradeDistributionCardProps {
  filterParams?: StatisticsFilterParams;
}

const GRADE_ORDER: DefectGrade[] = ['A', 'B', 'C', 'D', 'E'];

// Figma 시안(node 77-1454)의 등급 라벨("A (경미)" 등)은 이 통계 페이지 전용 워딩이다.
// features/dashboard의 등급 라벨과 문구가 다를 수 있으나, feature 간 직접 import 금지
// 컨벤션에 따라 별도로 유지한다(types.ts 상단 주석 참고).
const GRADE_LABELS: Record<DefectGrade, string> = {
  A: 'A (경미)',
  B: 'B (양호)',
  C: 'C (보통)',
  D: 'D (주의)',
  E: 'E (심각)',
};

// 등급 색상은 shared/components/charts/palette.ts의 기존 CHART_GRADE_COLORS를 그대로 재사용한다
// (dashboard/colors.ts GRADE_BG_CLASS와 동일 값으로 이미 팀이 확정한 팔레트 — Figma의
// green-500/lime-500/... 근사값과 거의 일치하며, 신규 hex를 임의로 도입하지 않는다).
export function StatisticsGradeDistributionCard({ filterParams }: StatisticsGradeDistributionCardProps) {
  const { data, isLoading, isError } = useStatisticsGradeDistribution(filterParams);
  const sorted = data
    ? [...data].sort((a, b) => GRADE_ORDER.indexOf(a.grade) - GRADE_ORDER.indexOf(b.grade))
    : [];

  return (
    <section className="flex min-h-72 flex-col justify-between bg-white border border-zinc-200 p-6">
      <h3 className="text-zinc-900 text-base font-medium leading-6">등급별 분포</h3>
      {isLoading && <LoadingSpinner />}
      {isError && <p className="dashboard-card-status">등급별 분포를 불러오지 못했습니다.</p>}
      {!isLoading && !isError && sorted.length === 0 && (
        <p className="dashboard-card-status">등록된 하자 등급 데이터가 없습니다.</p>
      )}
      {!isLoading && !isError && sorted.length > 0 && (
        <>
          <div className="mt-6">
            <DistributionBar
              ariaLabel="하자 등급 분포 막대 그래프"
              height={24}
              showLegend={false}
              segments={sorted.map((item) => ({
                key: item.grade,
                label: GRADE_LABELS[item.grade],
                percent: item.percent,
                color: CHART_GRADE_COLORS[item.grade],
              }))}
            />
          </div>
          <ul className="m-0 grid flex-1 auto-rows-min grid-cols-2 content-end list-none gap-x-8 gap-y-2 p-0 pt-8">
            {sorted.map((item) => (
              <li key={item.grade} className="flex items-center justify-between text-xs">
                <span className="flex items-center gap-1.5 text-zinc-900 font-medium">
                  <span
                    className="inline-block size-2 rounded-full"
                    style={{ backgroundColor: CHART_GRADE_COLORS[item.grade] }}
                  />
                  {GRADE_LABELS[item.grade]}
                </span>
                <span className="text-zinc-500">{item.percent}%</span>
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}
