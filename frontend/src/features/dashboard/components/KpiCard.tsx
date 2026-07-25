import { DASHBOARD_COLOR_CLASS } from '../colors';
import { formatChangeRate } from '../utils/formatChangeRate';

type Props = {
  label: string;
  value: string;
  changeRate: number;
  hasAlertDot?: boolean;
};

// kpi-col 반응형(1100px/720px 데스크톱 우선 breakpoint)·형제 순서 기반 보더 규칙은
// Tailwind 임의 variant(max-[…]:, first:/last:, [&:nth-child(2)])로 그대로 이식.
const KPI_COL_CLASS =
  `pl-7 pr-7 border-r ${DASHBOARD_COLOR_CLASS.kpiDividerBorder} first:pl-4 last:pr-1 last:border-r-0 ` +
  `max-[1100px]:px-5 max-[1100px]:py-3 max-[1100px]:border-b ${DASHBOARD_COLOR_CLASS.dividerBorderBottomNarrow} ` +
  'max-[1100px]:[&:nth-child(2)]:border-r-0 max-[720px]:border-r-0';

export function KpiCard({ label, value, changeRate, hasAlertDot = false }: Props) {
  const changeColorClass =
    changeRate < 0 ? DASHBOARD_COLOR_CLASS.dangerText : DASHBOARD_COLOR_CLASS.successText;

  // Figma 시안은 수치(예: "14")와 단위("개")를 서로 다른 폰트 크기(5xl/xl)로 분리 렌더링한다.
  // value가 "14개"처럼 합쳐진 문자열로 들어오므로, 여기서 숫자(콤마 포함)와 뒤따르는 단위를 분리한다.
  const valueMatch = value.match(/^([\d,]+)(.*)$/);
  const [numeric, unit] = valueMatch ? [valueMatch[1], valueMatch[2]] : [value, ''];

  return (
    // Figma 원본 dev-mode 추출 Tailwind 값 그대로 복원(2026-07-25) — 스크린샷 비교로 %를 추측하며
    // 여러 차례 흔들렸던 것을 중단하고, 최초 제공받은 기준값(라벨 text-sm, 수치 text-5xl,
    // 단위 text-xl, 증감율 text-sm)을 단일 진실로 고정한다. 더 이상 임의 축소하지 않는다.
    <div className={`${KPI_COL_CLASS} flex flex-col gap-1`}>
      <div className="flex items-center gap-1.5">
        {hasAlertDot && (
          <span
            className={`w-1.5 h-1.5 rounded-full ${DASHBOARD_COLOR_CLASS.alertDotBg} shrink-0`}
            aria-hidden="true"
          />
        )}
        <span className={`text-sm ${DASHBOARD_COLOR_CLASS.labelText} font-medium`}>{label}</span>
      </div>
      <p className="flex items-baseline gap-1.5 m-0">
        <span className="text-5xl font-semibold leading-none">{numeric}</span>
        {unit && <span className="text-xl font-medium leading-none">{unit}</span>}
        <span className={`text-sm font-normal ${changeColorClass}`}>
          {formatChangeRate(changeRate)}
        </span>
      </p>
    </div>
  );
}
