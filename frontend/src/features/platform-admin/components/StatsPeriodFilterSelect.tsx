import type { ChangeEvent } from 'react';

export type StatsPeriodFilterValue = '1M' | '3M' | '6M' | '1Y' | 'ALL';

const PERIOD_OPTIONS: { value: StatsPeriodFilterValue; label: string }[] = [
  { value: '1M', label: '최근 1개월' },
  { value: '3M', label: '최근 3개월' },
  { value: '6M', label: '최근 6개월' },
  { value: '1Y', label: '최근 1년' },
  { value: 'ALL', label: '전체' },
];

interface StatsPeriodFilterSelectProps {
  value: StatsPeriodFilterValue;
  onChange: (value: StatsPeriodFilterValue) => void;
}

// 기간 필터 — 로컬 state 전용, 조회 파라미터에는 연결하지 않는다. 백엔드에 기간별 통계 조회 API가
// 없어(#634, features/mypage/components/PeriodFilterSelect와 동일 전략 — HAJA-366 선례) 선택값은
// 화면 표시 목적으로만 쓴다.
export function StatsPeriodFilterSelect({ value, onChange }: StatsPeriodFilterSelectProps) {
  function handleChange(event: ChangeEvent<HTMLSelectElement>) {
    onChange(event.target.value as StatsPeriodFilterValue);
  }

  return (
    <select
      className="cursor-pointer rounded-full border border-border bg-surface px-4 py-2 text-sm font-medium text-text-default"
      value={value}
      onChange={handleChange}
      aria-label="조회 기간"
    >
      {PERIOD_OPTIONS.map((option) => (
        <option key={option.value} value={option.value}>
          {option.label}
        </option>
      ))}
    </select>
  );
}
