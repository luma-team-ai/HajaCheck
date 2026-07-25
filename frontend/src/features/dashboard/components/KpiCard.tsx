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
    // Figma 시안 폰트 비율 재정합(2026-07-24, 원본 대조): 라벨=text-sm/font-medium,
    // 수치=text-5xl(숫자만)+단위 text-xl 별도, 증감율 배지=text-sm/font-normal.
    <div className={`${KPI_COL_CLASS} flex flex-col gap-1`}>
      <div className="flex items-center gap-1.5">
        {hasAlertDot && (
          <span
            className={`w-1.5 h-1.5 rounded-full ${DASHBOARD_COLOR_CLASS.alertDotBg} shrink-0`}
            aria-hidden="true"
          />
        )}
        <span className={`text-xs ${DASHBOARD_COLOR_CLASS.labelText} font-medium`}>{label}</span>
      </div>
      <p className="flex items-baseline gap-1.5 m-0">
        <span className="text-4xl font-semibold leading-none">{numeric}</span>
        {unit && <span className="text-base font-medium leading-none">{unit}</span>}
        <span className={`text-xs font-normal ${changeColorClass}`}>
          {formatChangeRate(changeRate)}
        </span>
      </p>
    </div>
  );
}
