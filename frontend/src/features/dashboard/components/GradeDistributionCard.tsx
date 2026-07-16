import { useGradeDistribution } from '../hooks/useGradeDistribution';
import {
  getGradeBgClass,
  isGradeTotalValid,
  sortGradeDistribution,
  sumGradePercent,
} from '../utils/gradeDistribution';

export function GradeDistributionCard() {
  const { data, isLoading, isError } = useGradeDistribution();
  const sorted = data ? sortGradeDistribution(data) : [];

  // 스토리보드 DASH-01 V2: 등급별 비율 막대의 합계가 100%인지 검증 (부동소수 오차 허용)
  const totalPercent = sumGradePercent(sorted);
  const isTotalValid = isGradeTotalValid(sorted);

  return (
    <section className="dashboard-card">
      <h3 className="dashboard-card-title">하자 등급 분포</h3>

      {isLoading && <p className="dashboard-card-status">불러오는 중...</p>}
      {isError && <p className="dashboard-card-status">등급 분포를 불러오지 못했습니다.</p>}
      {!isLoading && !isError && sorted.length === 0 && (
        <p className="dashboard-card-status">등록된 하자 등급 데이터가 없습니다.</p>
      )}

      {!isLoading && !isError && sorted.length > 0 && (
        <>
          <div
            className="flex w-full h-3.5 rounded-full overflow-hidden bg-[#f0f1f3]"
            role="img"
            aria-label="하자 등급 분포 막대 그래프"
          >
            {sorted.map((item) => (
              <div
                key={item.grade}
                className={`h-full ${getGradeBgClass(item.grade)}`}
                style={{ width: `${item.percent}%` }}
              />
            ))}
          </div>
          <ul className="list-none mt-3.5 mb-0 mx-0 p-0 flex flex-nowrap gap-2.5 max-[1100px]:flex-wrap">
            {sorted.map((item) => (
              <li key={item.grade} className="flex items-center gap-1.5 text-[13px] text-[#555]">
                <span className={`inline-block w-2 h-2 rounded-full ${getGradeBgClass(item.grade)}`} />
                {item.grade} 등급 ({item.percent}%)
              </li>
            ))}
          </ul>
          {!isTotalValid && (
            <p className="dashboard-card-status mt-2 text-[#dc2626] text-[13px]" role="alert">
              등급 분포 합계가 100%가 아닙니다 (현재 {totalPercent.toFixed(1)}%) — 데이터를 확인해 주세요.
            </p>
          )}
        </>
      )}
    </section>
  );
}
