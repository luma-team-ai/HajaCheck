import { DASHBOARD_COLOR_CLASS, GRADE_BG_CLASS_LIGHT } from '../colors';
import { useGradeDistribution } from '../hooks/useGradeDistribution';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner';
import { isGradeTotalValid, sortGradeDistribution, sumGradePercent } from '../utils/gradeDistribution';

// `dashboard-card-status`(layout.css, un-layered)가 color:#999 / font-size:14px를 지정하므로,
// 경고 문구의 빨강·13px을 살리려면 두 유틸리티 모두 `!`가 필요하다(Cascade Layers — colors.ts 주석 참고).
const WARNING_CLASS = `dashboard-card-status mt-2 ${DASHBOARD_COLOR_CLASS.dangerTextImportant} text-[13px]!`;

export function GradeDistributionCard() {
  const { data, isLoading, isError } = useGradeDistribution();
  const sorted = data ? sortGradeDistribution(data) : [];

  // 스토리보드 DASH-01 V2: 등급별 비율 막대의 합계가 100%인지 검증 (부동소수 오차 허용)
  const totalPercent = sumGradePercent(sorted);
  const isTotalValid = isGradeTotalValid(sorted);

  return (
    <section className="dashboard-card">
      <h3 className="dashboard-card-title">하자 등급 분포</h3>

      {isLoading && <LoadingSpinner />}
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
                className={`h-full ${GRADE_BG_CLASS_LIGHT[item.grade]}`}
                style={{ width: `${item.percent}%` }}
              />
            ))}
          </div>
          {/* 각 라벨을 막대와 동일한 비율 너비로 배치해 해당 색상 세그먼트 바로 아래 오도록 정렬(Figma 시안, #556) */}
          <div className="flex w-full mt-3.5">
            {sorted.map((item) => (
              <div
                key={item.grade}
                className="flex min-w-0 flex-col items-center gap-1 text-[13px] text-[#555]"
                style={{ width: `${item.percent}%` }}
              >
                <span className={`inline-block w-2 h-2 rounded-full ${GRADE_BG_CLASS_LIGHT[item.grade]}`} />
                {/* 세그먼트 폭이 라벨보다 좁을 수 있어(예: E 5%) 말줄임으로 정렬을 유지한다(#556 리뷰 반영) */}
                <span className="w-full overflow-hidden text-ellipsis whitespace-nowrap text-center">
                  {item.grade} 등급 ({item.percent}%)
                </span>
              </div>
            ))}
          </div>
          {!isTotalValid && (
            <p className={WARNING_CLASS} role="alert">
              등급 분포 합계가 100%가 아닙니다 (현재 {totalPercent.toFixed(1)}%) — 데이터를 확인해 주세요.
            </p>
          )}
        </>
      )}
    </section>
  );
}
