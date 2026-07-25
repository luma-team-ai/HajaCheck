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
  `pl-7 pr-7 border-r ${DASHBOARD_COLOR_CLASS.kpiDividerBorder} first:pl-1 last:pr-1 last:border-r-0 ` +
  `max-[1100px]:px-5 max-[1100px]:py-3 max-[1100px]:border-b ${DASHBOARD_COLOR_CLASS.dividerBorderBottomNarrow} ` +
  'max-[1100px]:[&:nth-child(2)]:border-r-0 max-[720px]:border-r-0';

export function KpiCard({ label, value, changeRate, hasAlertDot = false }: Props) {
  const changeColorClass =
    changeRate < 0 ? DASHBOARD_COLOR_CLASS.dangerText : DASHBOARD_COLOR_CLASS.successText;

  return (
    // Figma 시안 세로 리듬 정합(2026-07-24): 라벨↔수치 간격을 gap-2(8px 플렉스 갭)로,
    // 수치 폰트를 text-5xl(48px)로 키워 시안의 "여유 있는" 카드 높이를 재현한다
    // (기존 mb-2.5+text-[28px]는 시안 대비 수치가 작아 세로 리듬이 눌려 보였음).
    <div className={`${KPI_COL_CLASS} flex flex-col gap-2`}>
      <div className="flex items-center gap-1.5">
        {hasAlertDot && (
          <span
            className={`w-1.5 h-1.5 rounded-full ${DASHBOARD_COLOR_CLASS.alertDotBg} shrink-0`}
            aria-hidden="true"
          />
        )}
        <span className={`text-[13px] ${DASHBOARD_COLOR_CLASS.labelText} font-semibold`}>{label}</span>
      </div>
      <p className="flex items-baseline gap-2 m-0">
        <span className="text-5xl font-extrabold leading-none">{value}</span>
        <span className={`text-[13px] font-bold ${changeColorClass}`}>
          {formatChangeRate(changeRate)}
        </span>
      </p>
    </div>
  );
}
