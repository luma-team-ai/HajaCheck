/** LineChart/BarChart가 공통으로 받는 시리즈 정의 — 값 필드 + 표시 이름/색상(선택). */
export interface ChartSeries {
  /** data 배열 각 항목에서 값으로 사용할 필드명 */
  dataKey: string;
  /** 범례/툴팁에 표시할 이름 (미지정 시 dataKey를 그대로 표시) */
  name?: string;
  /** 선/막대 색상 오버라이드 (미지정 시 CHART_SERIES_COLORS 순환 배정) */
  color?: string;
}
