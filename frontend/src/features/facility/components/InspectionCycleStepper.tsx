import type { ChangeEvent } from 'react';

const MIN_MONTHS = 1;
const MAX_MONTHS = 60;
const QUICK_OPTIONS: { label: string; months: number }[] = [
  { label: '3개월', months: 3 },
  { label: '6개월', months: 6 },
  { label: '1년', months: 12 },
];

type Props = {
  months: number;
  onChange: (months: number) => void;
};

// 주기 입력 — 스테퍼(−/+) + 단위(개월, 고정 표시) + 퀵칩(handoff §2)
export function InspectionCycleStepper({ months, onChange }: Props) {
  const decrement = () => onChange(Math.max(MIN_MONTHS, months - 1));
  const increment = () => onChange(Math.min(MAX_MONTHS, months + 1));

  const handleInputChange = (event: ChangeEvent<HTMLInputElement>) => {
    const parsed = Number(event.target.value);
    if (Number.isNaN(parsed)) {
      return;
    }
    // 소수 입력(예: 6.5)이 그대로 저장 요청 개월수로 전송되지 않도록 정수로 반올림(react-reviewer P3).
    onChange(Math.min(MAX_MONTHS, Math.max(MIN_MONTHS, Math.round(parsed))));
  };

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center gap-2">
        <button
          type="button"
          onClick={decrement}
          disabled={months <= MIN_MONTHS}
          aria-label="주기 1개월 감소"
          className="inline-flex h-10 w-10 items-center justify-center rounded-full border border-border bg-surface text-lg font-semibold disabled:opacity-40"
        >
          −
        </button>
        <input
          type="number"
          value={months}
          onChange={handleInputChange}
          min={MIN_MONTHS}
          max={MAX_MONTHS}
          aria-label="점검 주기(개월)"
          className="h-10 w-16 rounded-full border border-border text-center text-base font-semibold"
        />
        <button
          type="button"
          onClick={increment}
          disabled={months >= MAX_MONTHS}
          aria-label="주기 1개월 증가"
          className="inline-flex h-10 w-10 items-center justify-center rounded-full border border-border bg-surface text-lg font-semibold disabled:opacity-40"
        >
          +
        </button>
        <button
          type="button"
          disabled
          aria-label="단위: 개월(고정)"
          className="ml-1 inline-flex h-10 items-center gap-1 rounded-full border border-border bg-surface-muted px-3 text-sm text-text-default"
        >
          개월 <span aria-hidden="true">▾</span>
        </button>
      </div>

      <div className="flex gap-2" role="group" aria-label="주기 빠른 선택">
        {QUICK_OPTIONS.map((option) => {
          const isSelected = option.months === months;
          return (
            <button
              key={option.months}
              type="button"
              aria-pressed={isSelected}
              onClick={() => onChange(option.months)}
              className={`rounded-full px-4 py-1.5 text-sm font-medium ${
                isSelected
                  ? 'bg-primary text-surface'
                  : 'border border-border bg-surface text-text-default hover:bg-surface-muted'
              }`}
            >
              {option.label}
            </button>
          );
        })}
      </div>
    </div>
  );
}
