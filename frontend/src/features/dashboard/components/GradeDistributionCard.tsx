import { useGradeDistribution } from '../hooks/useGradeDistribution';
import {
  getGradeColor,
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
    <section className="dashboard-card grade-distribution-card">
      <h3 className="dashboard-card-title">하자 등급 분포</h3>

      {isLoading && <p className="dashboard-card-status">불러오는 중...</p>}
      {isError && <p className="dashboard-card-status">등급 분포를 불러오지 못했습니다.</p>}
      {!isLoading && !isError && sorted.length === 0 && (
        <p className="dashboard-card-status">등록된 하자 등급 데이터가 없습니다.</p>
      )}

      {!isLoading && !isError && sorted.length > 0 && (
        <>
          <div className="grade-bar" role="img" aria-label="하자 등급 분포 막대 그래프">
            {sorted.map((item) => (
              <div
                key={item.grade}
                className="grade-bar-segment"
                style={{ width: `${item.percent}%`, backgroundColor: getGradeColor(item.grade) }}
              />
            ))}
          </div>
          <ul className="grade-legend">
            {sorted.map((item) => (
              <li key={item.grade} className="grade-legend-item">
                <span className="grade-legend-dot" style={{ backgroundColor: getGradeColor(item.grade) }} />
                {item.grade} 등급 ({item.percent}%)
              </li>
            ))}
          </ul>
          {!isTotalValid && (
            <p className="dashboard-card-status grade-distribution-warning" role="alert">
              등급 분포 합계가 100%가 아닙니다 (현재 {totalPercent.toFixed(1)}%) — 데이터를 확인해 주세요.
            </p>
          )}
        </>
      )}
    </section>
  );
}
