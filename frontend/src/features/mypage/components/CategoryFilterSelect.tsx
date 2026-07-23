import type { ChangeEvent } from 'react';
import type { CounselType } from '../types';

export type CategoryFilterValue = CounselType | 'ALL';

const CATEGORY_OPTIONS: { value: CategoryFilterValue; label: string }[] = [
  { value: 'ALL', label: '전체 카테고리' },
  { value: 'SCENARIO_BOT', label: '시나리오 챗봇' },
  { value: 'AGENT_CONNECT', label: '상담원 연결' },
  { value: 'INQUIRY', label: '문의 남기기' },
];

type Props = {
  value: CategoryFilterValue;
  onChange: (value: CategoryFilterValue) => void;
};

// 카테고리(상담 유형) 필터 — PeriodFilterSelect와 동일 구조. 로컬 state 전용, 실제 조회
// 파라미터에는 연결하지 않는다. 상담 BE API가 전무해(controller/service/repo .gitkeep 빈 스켈레톤)
// 선택값은 화면 표시 목적으로만 쓴다(HAJA-371, #678).
export function CategoryFilterSelect({ value, onChange }: Props) {
  function handleChange(event: ChangeEvent<HTMLSelectElement>) {
    onChange(event.target.value as CategoryFilterValue);
  }

  return (
    <select
      className="cursor-pointer rounded-full border border-border bg-white px-4 py-2 text-sm font-medium text-text-default"
      value={value}
      onChange={handleChange}
      aria-label="상담 카테고리"
    >
      {CATEGORY_OPTIONS.map((option) => (
        <option key={option.value} value={option.value}>
          {option.label}
        </option>
      ))}
    </select>
  );
}
