import { LoadingSpinner } from '../../../shared/components/LoadingSpinner';
import { useDefectTypeDistribution } from '../hooks/useDefectTypeDistribution';
import type { StatisticsFilterParams } from '../types';

interface DefectTypeDistributionCardProps {
  filterParams?: StatisticsFilterParams;
}

// PRD §2 AI 탐지 유형별 분포. Figma 시안(node 77-1454)은 막대그래프가 아니라 유형별 가로 프로그레스바
// 목록(값이 클수록 진한 회색조)이라 shared BarChart 대신 이 카드 전용 커스텀 리스트로 구현한다.
// 퍼센트는 전체 하자 건수 대비 해당 유형의 비중(실데이터 기반, 하드코딩 아님).
const SHADE_SCALE = ['bg-zinc-900', 'bg-zinc-400', 'bg-zinc-300', 'bg-zinc-200', 'bg-zinc-200'];

export function DefectTypeDistributionCard({ filterParams }: DefectTypeDistributionCardProps) {
  const { data, isLoading, isError } = useDefectTypeDistribution(filterParams);

  const total = data?.reduce((sum, item) => sum + item.count, 0) ?? 0;
  const sorted = data ? [...data].sort((a, b) => b.count - a.count) : [];

  return (
    <section className="flex h-80 flex-col bg-white border border-zinc-200 p-6">
      <div className="mb-6 flex items-center justify-between">
        <h3 className="text-zinc-900 text-base font-medium leading-6">AI 탐지 유형별 분포</h3>
      </div>
      {isLoading && (
        <div className="my-auto flex flex-1 items-center justify-center py-8">
          <LoadingSpinner />
        </div>
      )}
      {isError && (
        <div className="my-auto flex flex-1 items-center justify-center py-8">
          <p className="dashboard-card-status">AI 탐지 유형별 분포를 불러오지 못했습니다.</p>
        </div>
      )}
      {!isLoading && !isError && sorted.length === 0 && (
        <div className="my-auto flex flex-1 items-center justify-center py-8">
          <p className="dashboard-card-status">등록된 AI 탐지 유형 데이터가 없습니다.</p>
        </div>
      )}
      {!isLoading && !isError && sorted.length > 0 && (
        <div className="flex flex-1 flex-col justify-evenly gap-4">
          {sorted.map((item, index) => {
            const percent = total > 0 ? Math.round((item.count / total) * 100) : 0;
            const isTop = index === 0;
            return (
              <div key={item.type} className="flex items-center gap-4">
                <span
                  className={`w-20 text-right text-xs ${
                    isTop ? 'text-zinc-900 font-medium' : 'text-zinc-500 font-medium'
                  }`}
                >
                  {item.type}
                </span>
                <div className="h-3 flex-1 overflow-hidden rounded-full bg-zinc-100">
                  <div
                    className={`h-3 rounded-full ${SHADE_SCALE[index] ?? 'bg-zinc-200'}`}
                    style={{ width: `${percent}%` }}
                  />
                </div>
                <span className={`w-8 text-xs ${isTop ? 'text-zinc-900 font-semibold' : 'text-zinc-500 font-normal'}`}>
                  {percent}%
                </span>
              </div>
            );
          })}
        </div>
      )}
    </section>
  );
}
