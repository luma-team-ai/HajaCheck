import { useLayoutEffect, useRef } from 'react';

interface LabeledTextAreaProps {
  label: string;
  value: string;
  onChange: (next: string) => void;
  readOnly: boolean;
  rows?: number;
  placeholder?: string;
  className?: string;
  labelClassName?: string;
  textareaClassName?: string;
  hideLabel?: boolean;
}

const FIELD_CLASSES =
  'w-full resize-none overflow-hidden rounded-2xl border border-zinc-200 bg-white px-4 py-3 text-sm leading-6 text-text-default outline-none transition placeholder:text-zinc-400 focus:border-zinc-400 focus:ring-2 focus:ring-zinc-200 read-only:cursor-default read-only:bg-zinc-50 read-only:text-text-muted';

// label이 textarea를 감싸므로 getByLabelText 기반 테스트와 접근성 이름을 그대로 유지한다.
export function LabeledTextArea({
  label,
  value,
  onChange,
  readOnly,
  rows = 3,
  placeholder,
  className = '',
  labelClassName = '',
  textareaClassName = '',
  hideLabel = false,
}: LabeledTextAreaProps) {
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);

  useLayoutEffect(() => {
    const textarea = textareaRef.current;
    if (!textarea) return;

    textarea.style.height = 'auto';
    textarea.style.height = `${textarea.scrollHeight}px`;
  }, [value]);

  return (
    <label className={`flex flex-col gap-2 ${className}`}>
      <span
        className={`${hideLabel ? 'sr-only' : 'text-xs font-medium tracking-wide text-zinc-700'} ${labelClassName}`}
      >
        {label}
      </span>
      <textarea
        ref={textareaRef}
        className={`${FIELD_CLASSES} ${textareaClassName}`}
        rows={rows}
        value={value}
        readOnly={readOnly}
        placeholder={placeholder}
        onChange={(event) => onChange(event.target.value)}
      />
    </label>
  );
}
