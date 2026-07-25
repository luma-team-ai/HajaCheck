// 월별 하자 추이/히트맵 X축 라벨 포맷터 — Figma 시안(node 77-1454)은 'yyyy-MM' 원본이 아니라
// 'M월' 형식(예: '2월')으로 축을 표기한다.
export function formatMonthLabel(month: string): string {
  const [, rawMonth] = month.split('-');
  const parsed = Number(rawMonth);
  return Number.isNaN(parsed) ? month : `${parsed}월`;
}
