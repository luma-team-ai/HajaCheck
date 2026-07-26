import { useEffect, useRef } from 'react';

type Props = {
  ariaLabel: string;
  checked: boolean;
  disabled?: boolean;
  indeterminate?: boolean;
  onChange: () => void;
};

// defect feature의 동일 컴포넌트(InspectionTable/DefectTable)와 시각·동작이 같지만 feature 간
// 직접 import 금지 컨벤션(React_코드_컨벤션.md §1)에 따라 로컬로 재정의한다.
export function SelectionCheckbox({ ariaLabel, checked, disabled = false, indeterminate = false, onChange }: Props) {
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (inputRef.current) {
      inputRef.current.indeterminate = indeterminate;
    }
  }, [indeterminate]);

  return (
    <input
      ref={inputRef}
      type="checkbox"
      aria-label={ariaLabel}
      checked={checked}
      disabled={disabled}
      onChange={onChange}
      onClick={(event) => event.stopPropagation()}
    />
  );
}
