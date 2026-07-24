import { useEffect, useRef } from 'react';

type SelectionCheckboxProps = {
  ariaLabel: string;
  checked: boolean;
  disabled?: boolean;
  indeterminate?: boolean;
  onChange: () => void;
};

// DefectTable(하자 단건)과 InspectionTable(점검 단위, HAJA-393/394·#725/#726)이 동일한 선택
// 체크박스 UI를 공유하도록 분리했다(원래 DefectTable.tsx 로컬 컴포넌트였음).
export function SelectionCheckbox({
  ariaLabel,
  checked,
  disabled = false,
  indeterminate = false,
  onChange,
}: SelectionCheckboxProps) {
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (inputRef.current) {
      inputRef.current.indeterminate = indeterminate;
    }
  }, [indeterminate]);

  return (
    <input
      ref={inputRef}
      className="defect-list-table__select"
      type="checkbox"
      aria-label={ariaLabel}
      checked={checked}
      disabled={disabled}
      onChange={onChange}
      onClick={(event) => event.stopPropagation()}
    />
  );
}
