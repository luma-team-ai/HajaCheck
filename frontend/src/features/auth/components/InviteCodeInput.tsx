import { useRef } from 'react';
import type { ClipboardEvent, KeyboardEvent } from 'react';

const CODE_LENGTH = 6;
// 6자리를 3-3으로 나눠 가운데 대시를 표시(Figma "초대 코드 입력" 시안) — 이 값이 바뀌면
// splitIndex 계산과 대시 렌더 위치도 함께 바꿔야 한다.
const SPLIT_INDEX = 3;

type Props = {
  value: string;
  onChange: (value: string) => void;
  hasError?: boolean;
  disabled?: boolean;
};

// 발급받은 6자리 초대 코드를 한 칸씩 입력하는 컴포넌트(HAJA, #799) — 코드 형식(영문/숫자 여부)은
// 발급 측(#794, 백엔드 미구현)이 아직 확정 전이라 우선 영문 대문자+숫자를 모두 허용한다.
export function InviteCodeInput({ value, onChange, hasError = false, disabled = false }: Props) {
  const inputRefs = useRef<Array<HTMLInputElement | null>>([]);
  const digits = Array.from({ length: CODE_LENGTH }, (_, index) => value[index] ?? '');

  const setDigitAt = (index: number, char: string) => {
    const next = digits.slice();
    next[index] = char;
    onChange(next.join('').replace(/\s+$/, ''));
  };

  const handleChange = (index: number, rawInput: string) => {
    // 한 칸에 여러 글자가 들어오면(빠른 타이핑·IME) 마지막 한 글자만 취한다.
    const char = rawInput.slice(-1).toUpperCase().replace(/[^A-Z0-9]/, '');
    setDigitAt(index, char);
    if (char && index < CODE_LENGTH - 1) {
      inputRefs.current[index + 1]?.focus();
    }
  };

  const handleKeyDown = (index: number, event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'Backspace' && !digits[index] && index > 0) {
      inputRefs.current[index - 1]?.focus();
      setDigitAt(index - 1, '');
    } else if (event.key === 'ArrowLeft' && index > 0) {
      inputRefs.current[index - 1]?.focus();
    } else if (event.key === 'ArrowRight' && index < CODE_LENGTH - 1) {
      inputRefs.current[index + 1]?.focus();
    }
  };

  const handlePaste = (event: ClipboardEvent<HTMLInputElement>) => {
    event.preventDefault();
    const pasted = event.clipboardData
      .getData('text')
      .toUpperCase()
      .replace(/[^A-Z0-9]/g, '')
      .slice(0, CODE_LENGTH);
    if (!pasted) return;

    onChange(pasted);
    const focusIndex = Math.min(pasted.length, CODE_LENGTH - 1);
    inputRefs.current[focusIndex]?.focus();
  };

  return (
    <div
      role="group"
      aria-label="초대 코드 6자리"
      className="flex items-center justify-center gap-1.5"
    >
      {digits.map((digit, index) => (
        <div key={index} className="flex items-center gap-1.5">
          {index === SPLIT_INDEX && (
            <span aria-hidden="true" className="text-sm text-zinc-400">
              -
            </span>
          )}
          <input
            ref={(el) => {
              inputRefs.current[index] = el;
            }}
            type="text"
            inputMode="text"
            maxLength={1}
            autoComplete="off"
            aria-label={`초대 코드 ${index + 1}번째 자리`}
            disabled={disabled}
            value={digit}
            onChange={(event) => handleChange(index, event.target.value)}
            onKeyDown={(event) => handleKeyDown(index, event)}
            onPaste={handlePaste}
            className={`h-10 w-10 rounded-full border bg-zinc-100 text-center text-base font-semibold text-zinc-900 outline-none transition-colors focus:border-zinc-900 focus:bg-white focus:ring-2 focus:ring-zinc-900 disabled:cursor-not-allowed disabled:opacity-60 ${
              hasError ? 'border-red-400' : 'border-transparent'
            }`}
          />
        </div>
      ))}
    </div>
  );
}
