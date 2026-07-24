import { ERROR_LOG_LEVEL_BADGE_CLASS } from '../monitoring.constants';
import type { ErrorLogLevel } from '../monitoring.types';

export type ErrorLogLevelFilter = ErrorLogLevel | 'ALL';

const LEVEL_OPTIONS: ErrorLogLevelFilter[] = ['ALL', 'ERROR', 'WARN'];

const LEVEL_OPTION_LABEL: Record<ErrorLogLevelFilter, string> = {
  ALL: '전체',
  ERROR: 'ERROR',
  WARN: 'WARN',
};

const ALL_BADGE_CLASS = 'bg-surface-muted text-text-muted';

interface ErrorLogFilterBarProps {
  date: string;
  onDateChange: (date: string) => void;
  level: ErrorLogLevelFilter;
  onLevelChange: (level: ErrorLogLevelFilter) => void;
}

// 에러 로그 검색 조건 — 날짜(YYYY-MM-DD) 검색 + ERROR/WARN 라벨 클릭 필터(#729 후속). 라벨을 다시
// 누르면 '전체'로 되돌아가지 않고 명시적으로 '전체' 옵션을 눌러야 해제되도록, 활성 라벨을 aria-pressed로 표시한다.
export function ErrorLogFilterBar({ date, onDateChange, level, onLevelChange }: ErrorLogFilterBarProps) {
  return (
    <div className="flex flex-wrap items-center gap-3">
      <input
        type="date"
        aria-label="날짜 검색"
        value={date}
        onChange={(event) => onDateChange(event.target.value)}
        className="h-9 rounded-lg border border-border bg-surface px-3 text-[13px] text-text-default"
      />
      <div className="flex items-center gap-1.5" role="group" aria-label="레벨 필터">
        {LEVEL_OPTIONS.map((option) => {
          const isActive = level === option;
          const badgeClass = option === 'ALL' ? ALL_BADGE_CLASS : ERROR_LOG_LEVEL_BADGE_CLASS[option];
          return (
            <button
              key={option}
              type="button"
              aria-pressed={isActive}
              onClick={() => onLevelChange(option)}
              className={`inline-flex items-center rounded-md px-2.5 py-1 text-[11px] font-bold transition-opacity duration-150 ${badgeClass} ${
                isActive ? '' : 'opacity-40 hover:opacity-70'
              }`}
            >
              {LEVEL_OPTION_LABEL[option]}
            </button>
          );
        })}
      </div>
    </div>
  );
}
