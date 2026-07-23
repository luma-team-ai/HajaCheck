import { FACILITY_INITIAL_GRADE_OPTIONS } from '../constants';
import {
  FACILITY_INITIAL_GRADE_SELECTED_CLASS,
  FACILITY_INITIAL_GRADE_UNSELECTED_CLASS,
} from '../facilityInitialGradeColors';
import { LABEL_CLASSES } from '../formClasses';
import type { FacilityInitialGrade } from '../types';

interface Props {
  value: FacilityInitialGrade | '';
  onChange: (grade: FacilityInitialGrade | '') => void;
}

// 등록 모달 "초기 등급 설정(선택)" — Figma 시안의 A~E pill 토글, 단일 선택. 미선택 허용(#628).
// 이미 선택된 등급을 다시 클릭하면 선택 해제된다.
export function FacilityInitialGradeSelect({ value, onChange }: Props) {
  const handleClick = (grade: FacilityInitialGrade) => {
    onChange(value === grade ? '' : grade);
  };

  return (
    <div className="flex flex-col gap-1">
      <span className={LABEL_CLASSES}>초기 등급 설정 (선택)</span>
      <div className="flex gap-2" role="group" aria-label="초기 등급 설정">
        {FACILITY_INITIAL_GRADE_OPTIONS.map((grade) => {
          const isSelected = value === grade;
          return (
            <button
              key={grade}
              type="button"
              aria-pressed={isSelected}
              onClick={() => handleClick(grade)}
              className={`h-9 w-9 rounded-full border text-sm font-bold transition ${
                isSelected
                  ? FACILITY_INITIAL_GRADE_SELECTED_CLASS[grade]
                  : FACILITY_INITIAL_GRADE_UNSELECTED_CLASS
              }`}
            >
              {grade}
            </button>
          );
        })}
      </div>
    </div>
  );
}
