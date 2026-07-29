import { DistributionBar } from '../../../shared/components/charts/DistributionBar';
import { CHART_GRADE_COLORS } from '../../../shared/components/charts/palette';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner';
import { useStatisticsGradeDistribution } from '../hooks/useStatisticsGradeDistribution';
import type { DefectGrade, StatisticsFilterParams } from '../types';

interface StatisticsGradeDistributionCardProps {
  filterParams?: StatisticsFilterParams;
}

const GRADE_ORDER: DefectGrade[] = ['A', 'B', 'C', 'D', 'E'];

const GRADE_LABELS: Record<DefectGrade, string> = {
  A: 'A (경미)',
  B: 'B (양호)',
  C: 'C (보통)',
  D: 'D (주의)',
  E: 'E (심각)',
};

export function StatisticsGradeDistributionCard({ filterParams }: StatisticsGradeDistributionCardProps) {
  const { data, isLoading, isError } = useStatisticsGradeDistribution(filterParams);
  const sorted = data
    ? [...data].sort((a, b) => GRADE_ORDER.indexOf(a.grade) - GRADE_ORDER.indexOf(b.grade))
    : [];

  return (
    <section className="flex h-full min-h-[320px] flex-col justify-between bg-white border border-zinc-200 p-6">
      <h3 className="text-zinc-900 text-base font-medium leading-6">등급별 분포</h3>
      {isLoading && <LoadingSpinner />}
      {isError && <p className="dashboard-card-status">등급별 분포를 불러오지 못했습니다.</p>}
      {!isLoading && !isError && sorted.length === 0 && (
        <p className="dashboard-card-status">등록된 하자 등급 데이터가 없습니다.</p>
      )}
      {!isLoading && !isError && sorted.length > 0 && (
        <div className="flex flex-1 flex-col justify-between mt-4">
          <div className="mt-2">
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
          <ul className="m-0 grid auto-rows-min grid-cols-2 list-none gap-x-8 gap-y-2.5 p-0 pt-5 mt-auto">
            {sorted.map((item) => (
              <li key={item.grade} className="flex items-center justify-between text-xs">
                <span className="flex items-center gap-1.5 text-zinc-900 font-medium">
                  <span
                    className="inline-block size-2 rounded-full shrink-0"
                    style={{ backgroundColor: CHART_GRADE_COLORS[item.grade] }}
                  />
                  {GRADE_LABELS[item.grade]}
                </span>
                <span className="text-zinc-500 font-medium">{item.percent}%</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}
