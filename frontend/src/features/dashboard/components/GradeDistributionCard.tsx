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
      {/* text-xl!/mb-5!: 공용 dashboard-card-title(15px, margin-bottom 12px, un-layered)을
          Figma 원본 dev-mode 값(text-xl=20px, mb=20px)으로 override(un-layered CSS라 `!` 필요, colors.ts 참고) */}
      <h3 className="dashboard-card-title text-xl! mb-5!">하자 등급 분포</h3>

      {isLoading && <LoadingSpinner />}
      {isError && <p className="dashboard-card-status">등급 분포를 불러오지 못했습니다.</p>}
      {!isLoading && !isError && sorted.length === 0 && (
        <p className="dashboard-card-status">등록된 하자 등급 데이터가 없습니다.</p>
      )}

      {!isLoading && !isError && sorted.length > 0 && (
        <>
          <div
            className="flex w-full h-7 rounded-full overflow-hidden bg-[#f0f1f3]"
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
          {/* Figma 재정합(2026-07-24): 라벨을 막대 세그먼트와 동일 비율 너비로 배치하던 기존 방식(#556)은
              편중된 분포(예: A 89%)에서 소수 등급 라벨이 말줄임(...)으로 잘려 읽을 수 없는 문제가 있었다.
              시안(2-1)처럼 5개 라벨을 각자 내용 너비 그대로, justify-between으로 한 행에 고르게 펼쳐
              전부 온전히 읽히도록 변경 — 폭 고정/shrink 계산(#565·#580 P2 우회책)이 전부 불필요해진다. */}
          <ul className="list-none m-0 flex w-full flex-wrap justify-between gap-2 mt-3.5 p-0">
            {sorted.map((item) => (
              <li
                key={item.grade}
                className="flex items-center gap-2 text-sm text-zinc-900 whitespace-nowrap"
              >
                <span className={`inline-block w-2 h-2 rounded-full ${GRADE_BG_CLASS_LIGHT[item.grade]}`} />
                <span>
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
