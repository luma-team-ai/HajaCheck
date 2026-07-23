import { DASHBOARD_COLOR_CLASS, GRADE_BG_CLASS_LIGHT } from '../colors';
import { useGradeDistribution } from '../hooks/useGradeDistribution';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner';
import { isGradeTotalValid, sortGradeDistribution, sumGradePercent } from '../utils/gradeDistribution';

// `dashboard-card-status`(layout.css, un-layered)가 color:#999 / font-size:14px를 지정하므로,
// 경고 문구의 빨강·13px을 살리려면 두 유틸리티 모두 `!`가 필요하다(Cascade Layers — colors.ts 주석 참고).
const WARNING_CLASS = `dashboard-card-status mt-2 ${DASHBOARD_COLOR_CLASS.dangerTextImportant} text-[13px]!`;

// percent=0인 등급 라벨이 폭 0%로 사라지는 것을 막는 최소 폭(px 고정, #565 P2 재검수 반영) —
// %로 주면 flex-shrink가 전체 항목(0%가 아닌 항목 포함)을 비례 축소시켜 막대와의 정렬이 깨진다.
const MIN_LABEL_WIDTH_PX = 40;

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
          {/* 각 라벨을 막대와 동일한 비율 너비로 배치해 해당 색상 세그먼트 바로 아래 오도록 정렬(Figma 시안, #556).
              percent=0인 등급(BE가 A~E 5개를 항상 반환)은 폭 0%가 되어 라벨이 완전히 사라지므로 최소 px 폭을
              보장한다(#565 P2). shrink-0은 0%(고정 px) 항목에만 걸어야 한다 — 전항목에 걸면 총 폭이
              100% + (0%항목수 × 40px)가 되어도 아무것도 줄어들 수 없어 카드 밖으로 오버플로우한다(#580 P2).
              percent>0 항목은 shrink 허용 상태로 둬서 0%항목의 고정폭만큼 비례 축소되어 총합이 100%로 맞춰진다.
              리스트 시맨틱도 유지(#565 P3). */}
          <ul className="list-none m-0 flex w-full mt-3.5 p-0">
            {sorted.map((item) => (
              <li
                key={item.grade}
                className={`flex min-w-0 flex-col items-center gap-1 text-[13px] text-[#555] ${
                  item.percent > 0 ? '' : 'shrink-0'
                }`}
                style={
                  item.percent > 0
                    ? { width: `${item.percent}%` }
                    : { width: `${MIN_LABEL_WIDTH_PX}px` }
                }
              >
                <span className={`inline-block w-2 h-2 rounded-full ${GRADE_BG_CLASS_LIGHT[item.grade]}`} />
                {/* 세그먼트 폭이 라벨보다 좁을 수 있어(예: E 5%) 말줄임으로 정렬을 유지한다(#556 리뷰 반영) */}
                <span className="w-full overflow-hidden text-ellipsis whitespace-nowrap text-center">
                  {item.grade} 등급 ({item.percent}%)
                </span>
              </li>
            ))}
          </ul>
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
