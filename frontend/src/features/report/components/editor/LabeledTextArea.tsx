interface LabeledTextAreaProps {
  label: string;
  value: string;
  onChange: (next: string) => void;
  readOnly: boolean;
  rows?: number;
  placeholder?: string;
}

const FIELD_CLASSES =
  'w-full rounded-lg border border-border bg-surface p-2 text-sm text-text-default disabled:cursor-not-allowed disabled:bg-surface-muted disabled:text-text-muted';

// <label>이 <span> 라벨 + <textarea>를 감싸는 구조 — `getByLabelText('점검 목적')` 등
// 역할 기반 쿼리가 textarea를 잡도록 보존(기존 Field 컴포넌트와 동일 패턴).
export function LabeledTextArea({
  label,
  value,
  onChange,
  readOnly,
  rows = 3,
  placeholder,
}: LabeledTextAreaProps) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-xs font-medium text-text-muted">{label}</span>
      <textarea
        className={FIELD_CLASSES}
        rows={rows}
        value={value}
        disabled={readOnly}
        placeholder={placeholder}
        onChange={(e) => onChange(e.target.value)}
      />
    </label>
  );
}
