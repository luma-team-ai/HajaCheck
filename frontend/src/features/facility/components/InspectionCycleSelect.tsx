import type { ChangeEvent } from 'react';
import type { InspectionCycleOption } from '../types';

type Props = {
  label: string;
  options: InspectionCycleOption[];
  value: number;
  onChange: (cycle: number) => void;
};

// 회차 선택 드롭다운 — "7회차 2026-03-18" 형식(#489 스펙).
export function InspectionCycleSelect({ label, options, value, onChange }: Props) {
  const handleChange = (event: ChangeEvent<HTMLSelectElement>) => {
    onChange(Number(event.target.value));
  };

  return (
    <select
      aria-label={label}
      value={value}
      onChange={handleChange}
      className="rounded-full border border-border bg-surface px-3 py-1.5 text-sm font-semibold text-text-default"
    >
      {options.map((option) => (
        <option key={option.cycle} value={option.cycle}>
          {option.cycle}회차 {option.date}
        </option>
      ))}
    </select>
  );
}