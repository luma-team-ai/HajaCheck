import {
  BarChart,
  CHART_COLORS,
  CHART_GRADE_COLORS,
  DistributionBar,
  LineChart,
  PieChart,
} from '../../shared/components/charts';
import {
  toGradeDistributionChartData,
  toInspectionTrendChartData,
  toStatusDistributionChartData,
} from './chartShowcase.adapters';
import type { InspectionTrendChartItem } from './chartShowcase.adapters';
import { showcaseGradeDistribution, showcaseRecentInspections } from './chartShowcase.mock';

const inspectionTrend = toInspectionTrendChartData(showcaseRecentInspections);
const gradeDistribution = toGradeDistributionChartData(showcaseGradeDistribution);
const statusDistribution = toStatusDistributionChartData(showcaseRecentInspections);

const GRADE_COLORS = Object.values(CHART_GRADE_COLORS);
const STATUS_COLORS = [
  CHART_COLORS.accent,
  CHART_GRADE_COLORS.C,
  CHART_COLORS.warning,
  CHART_COLORS.success,
] as const;

const CARD_CLASS = 'rounded-xl border border-border bg-surface p-6 shadow-sm';

export function ChartShowcasePage() {
  return (
    <main className="min-h-screen bg-surface-muted px-6 py-10 text-text-default">
      <div className="mx-auto max-w-7xl">
        <header className="mb-8">
          <p className="mb-2 text-sm font-semibold text-accent">DEV ONLY · HAJA-249 / GitHub #373</p>
          <h1 className="m-0 text-3xl font-bold text-heading">Recharts 공용 컴포넌트 쇼케이스</h1>
          <p className="mt-3 max-w-3xl text-sm leading-6 text-text-muted">
            Dashboard API 응답 DTO와 동일한 형식의 가상 데이터를 차트 표현 모델로 변환해 렌더링합니다. 실제 사용자·시설
            데이터는 포함하지 않습니다.
          </p>
        </header>

        <section className="grid gap-6 lg:grid-cols-2" aria-label="차트 정상 데이터 예시">
          <article className={`${CARD_CLASS} lg:col-span-2`}>
            <h2 className="mb-1 mt-0 text-lg font-semibold text-heading">최근 점검 하자 추이</h2>
            <p className="mb-5 mt-0 text-sm text-text-muted">RecentInspectionItem DTO의 점검일·하자 건수 사용</p>
            <LineChart<InspectionTrendChartItem>
              data={inspectionTrend}
              xKey="inspectedAt"
              series={[{ dataKey: 'defectCount', name: '발견 하자' }]}
              ariaLabel="최근 점검별 발견 하자 건수 추이 선 차트"
              valueFormatter={(value) => `${value}건`}
            />
          </article>

          <article className={CARD_CLASS}>
            <h2 className="mb-1 mt-0 text-lg font-semibold text-heading">하자 등급 분포</h2>
            <p className="mb-5 mt-0 text-sm text-text-muted">GradeDistributionItem DTO와 공용 성공 색상 사용</p>
            <BarChart
              data={gradeDistribution}
              xKey="grade"
              series={[
                {
                  dataKey: 'percent',
                  name: '비율',
                  color: CHART_GRADE_COLORS.A,
                },
              ]}
              ariaLabel="A부터 E까지 하자 등급별 비율 막대 차트"
              valueFormatter={(value) => `${value}%`}
            />
          </article>

          <article className={`${CARD_CLASS} lg:col-span-2`}>
            <h2 className="mb-1 mt-0 text-lg font-semibold text-heading">하자 등급 분포 (DistributionBar)</h2>
            <p className="mb-5 mt-0 text-sm text-text-muted">
              GradeDistributionItem DTO를 세그먼트 바로 표현 — 대시보드 GradeDistributionCard와 동일 시각을
              등급에 종속되지 않는 범용 컴포넌트로 일반화
            </p>
            <DistributionBar
              ariaLabel="A부터 E까지 하자 등급별 비율 분포 바"
              segments={gradeDistribution.map((item) => ({
                key: item.grade,
                label: `${item.grade} 등급`,
                percent: item.percent,
                color: CHART_GRADE_COLORS[item.grade],
              }))}
            />
          </article>

          <article className={CARD_CLASS}>
            <h2 className="mb-1 mt-0 text-lg font-semibold text-heading">점검 처리 상태</h2>
            <p className="mb-5 mt-0 text-sm text-text-muted">RecentInspectionItem DTO를 상태별 건수로 집계</p>
            <PieChart
              data={statusDistribution}
              dataKey="count"
              nameKey="status"
              itemKey="status"
              colors={STATUS_COLORS}
              innerRadius="48%"
              ariaLabel="점검 처리 상태별 건수 도넛 차트"
              valueFormatter={(value) => `${value}건`}
            />
          </article>
        </section>

        <section className="mt-6 grid gap-6 lg:grid-cols-2" aria-label="차트 경계 상태 예시">
          <article className={CARD_CLASS}>
            <h2 className="mb-1 mt-0 text-lg font-semibold text-heading">등급 팔레트 파이 차트</h2>
            <p className="mb-5 mt-0 text-sm text-text-muted">사용자 지정 색상과 긴 범례 확인</p>
            <PieChart
              data={gradeDistribution}
              dataKey="percent"
              nameKey="grade"
              itemKey="grade"
              colors={GRADE_COLORS}
              ariaLabel="공용 등급 팔레트를 적용한 하자 등급 비율 파이 차트"
              valueFormatter={(value) => `${value}%`}
            />
          </article>

          <article className={CARD_CLASS}>
            <h2 className="mb-1 mt-0 text-lg font-semibold text-heading">빈 데이터</h2>
            <p className="mb-5 mt-0 text-sm text-text-muted">조회 결과가 없을 때 공통 안내 상태</p>
            <LineChart<InspectionTrendChartItem>
              data={[]}
              xKey="inspectedAt"
              series={[{ dataKey: 'defectCount', name: '발견 하자' }]}
              ariaLabel="빈 점검 추이 차트"
              emptyMessage="선택한 기간에 점검 데이터가 없습니다."
            />
          </article>

          <article className={CARD_CLASS}>
            <h2 className="mb-1 mt-0 text-lg font-semibold text-heading">빈 분포 바</h2>
            <p className="mb-5 mt-0 text-sm text-text-muted">등급 분포 데이터가 없을 때 공통 안내 상태</p>
            <DistributionBar ariaLabel="빈 등급 분포 바" segments={[]} />
          </article>
        </section>
      </div>
    </main>
  );
}
