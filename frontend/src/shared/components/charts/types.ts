import type { ReactNode } from 'react';

export type ChartValue = number | string | ReadonlyArray<number | string>;

export type ChartValueFormatter = (value: ChartValue) => ReactNode;

/** 모든 공용 차트가 공유하는 표시 옵션. */
export interface ChartBaseProps {
  /** 스크린리더가 차트의 목적을 이해할 수 있는 설명 */
  ariaLabel: string;
  /** 차트 높이(px) */
  height?: number;
  /** 빈 데이터일 때 표시할 안내 문구 */
  emptyMessage?: string;
  /** 툴팁 값 표시 형식 */
  valueFormatter?: ChartValueFormatter;
}

/** LineChart/BarChart가 공통으로 받는 시리즈 정의 — 값 필드 + 표시 이름/색상(선택). */
export interface ChartSeries<T extends object> {
  /** data 배열 각 항목에서 값으로 사용할 필드명 */
  dataKey: keyof T & string;
  /** 범례/툴팁에 표시할 이름 (미지정 시 dataKey를 그대로 표시) */
  name?: string;
  /** 선/막대 색상 오버라이드 (미지정 시 CHART_SERIES_COLORS 순환 배정) */
  color?: string;
}
