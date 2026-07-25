// Figma 시안(node 77-1454) KPI 카드 4종(총 탐지 하자/평균 처리일/조치 완료율/진행성 하자) 공통 셀.
// 변화율 배지는 Figma가 단순 위/아래 삼각형 아이콘 하나 + 수치로 표시한다(사각형+대각선 화살표 아님).
// 단위는 카드마다 다르다(총 탐지 하자·조치 완료율=%, 평균 처리일=일, 진행성 하자=건수 그대로).
interface Props {
  label: string;
  value: string;
  changeRate: number;
  /** 변화율 뒤에 붙는 단위. 기본값 '%'. */
  changeUnit?: string;
  /** Figma 스펙: KPI 카드 4개 중 마지막 카드만 하단 보더가 없다. */
  showDivider?: boolean;
}

export function StatisticsKpiCard({ label, value, changeRate, changeUnit = '%', showDivider = false }: Props) {
  const arrow = changeRate > 0 ? '▲' : changeRate < 0 ? '▼' : '';
  const changeText = `${Math.abs(changeRate)}${changeUnit}`;

  return (
    <div
      className={`flex-1 h-36 p-6 flex flex-col justify-between items-start ${
        showDivider ? 'border-b border-zinc-200' : ''
      }`}
    >
      <span className="text-zinc-500 text-sm font-medium">{label}</span>
      <div className="pt-6 flex items-end gap-2">
        <span className="text-zinc-900 text-5xl font-semibold leading-10">{value}</span>
        <span className="flex items-center gap-1 pb-1 text-zinc-500 text-sm font-normal">
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
