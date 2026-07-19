import type { ReactNode } from 'react';

export type ChartValue = number | string | ReadonlyArray<number | string>;

export type ChartValueFormatter = (value: ChartValue) => ReactNode;

/** LineChart/BarChart/PieChart가 공유하는 기본 높이(px). */
export const DEFAULT_CHART_HEIGHT = 300;

/** 모든 공용 차트·DistributionBar가 공유하는 기본 빈 데이터 안내 문구. */
export const DEFAULT_CHART_EMPTY_MESSAGE = '표시할 데이터가 없습니다.';

/**
 * recharts Tooltip의 formatter prop에 valueFormatter를 그대로 전달할 수 있도록 감싼다.
 * valueFormatter 미지정 시 undefined를 반환해 recharts 기본 포맷을 그대로 사용하게 한다.
 * recharts Formatter 타입의 value 매개변수(ValueType | undefined)가 ChartValue보다 넓어
 * unknown으로 받아 좁힌다 — 런타임엔 항상 유효한 ChartValue.
 */
export function wrapTooltipFormatter(valueFormatter?: ChartValueFormatter) {
  if (!valueFormatter) return undefined;
  return (value: unknown) => (value === undefined ? '' : valueFormatter(value as ChartValue));
}

type KeyOfValue<T extends object, TValue> = {
  [K in keyof T]-?: Exclude<T[K], null | undefined> extends TValue ? K : never;
}[keyof T] & string;

/** Recharts의 값 축/조각 크기에 사용할 수 있는 숫자 필드명. */
export type ChartNumericKey<T extends object> = KeyOfValue<T, number>;

/** X축 범주·범례·안정 키에 사용할 수 있는 원시값 필드명. */
export type ChartCategoryKey<T extends object> = KeyOfValue<T, string | number>;

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
  dataKey: ChartNumericKey<T>;
  /** 범례/툴팁에 표시할 이름 (미지정 시 dataKey를 그대로 표시) */
  name?: string;
  /** 선/막대 색상 오버라이드 (미지정 시 CHART_SERIES_COLORS 순환 배정) */
  color?: string;
}
