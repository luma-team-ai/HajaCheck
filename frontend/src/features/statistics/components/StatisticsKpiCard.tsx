// Figma 시안(node 77-1454) KPI 카드 4종(총 탐지 하자/평균 처리일/조치 완료율/진행성 하자) 공통 셀.
// 변화율 배지는 Figma가 단순 위/아래 삼각형 아이콘 하나 + 수치로 표시한다.
interface Props {
  label: string;
  value: string;
  changeRate: number;
  /** 변화율 뒤에 붙는 단위. 기본값 '%'. */
  changeUnit?: string;
  isLast?: boolean;
}

export function StatisticsKpiCard({ label, value, changeRate, changeUnit = '%' }: Props) {
  const arrow = changeRate > 0 ? '▲' : changeRate < 0 ? '▼' : '';
  const changeText = `${Math.abs(changeRate)}${changeUnit}`;

  return (
    <div className="flex-1 min-w-0 h-36 p-5 sm:p-6 flex flex-col justify-between items-start overflow-hidden">
      <span className="text-zinc-500 text-sm font-medium truncate w-full">{label}</span>
      <div className="pt-4 sm:pt-6 flex items-baseline gap-2 min-w-0 w-full flex-wrap overflow-hidden">
        <span className="text-zinc-900 text-3xl sm:text-4xl xl:text-5xl font-semibold leading-none tracking-tight">
          {value}
        </span>
        <span className="flex items-center gap-0.5 pb-0.5 text-zinc-500 text-xs sm:text-sm font-normal whitespace-nowrap shrink-0">
          {arrow && (
            <span className="text-[10px]" aria-hidden="true">
              {arrow}
            </span>
          )}
          {changeText}
        </span>
      </div>
    </div>
  );
}
