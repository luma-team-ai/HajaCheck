import type { ChangeEvent } from 'react';

export type PeriodFilterValue = '1M' | '3M' | '6M' | '1Y' | 'ALL';

const PERIOD_OPTIONS: { value: PeriodFilterValue; label: string }[] = [
  { value: '1M', label: '최근 1개월' },
  { value: '3M', label: '최근 3개월' },
  { value: '6M', label: '최근 6개월' },
  { value: '1Y', label: '최근 1년' },
  { value: 'ALL', label: '전체' },
];

type Props = {
  value: PeriodFilterValue;
  onChange: (value: PeriodFilterValue) => void;
};

// 기간 필터 — 로컬 state 전용, 실제 조회 파라미터에는 연결하지 않는다. BE에 기간별 조회 API가
// 없어(grep 0건, 후속 BE 연동) 선택값은 화면 표시 목적으로만 쓴다(HAJA-366, #668).
export function PeriodFilterSelect({ value, onChange }: Props) {
  function handleChange(event: ChangeEvent<HTMLSelectElement>) {
    onChange(event.target.value as PeriodFilterValue);
  }

  return (
    <select
      className="cursor-pointer rounded-full border border-border bg-white px-4 py-2 text-sm font-medium text-text-default"
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
