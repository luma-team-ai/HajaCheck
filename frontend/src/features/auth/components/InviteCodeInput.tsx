import { useState, useRef } from 'react';
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
// 발급 측(#794)이 영문 대문자+숫자를 모두 허용하므로 그대로 맞춘다.
export function InviteCodeInput({ value, onChange, hasError = false, disabled = false }: Props) {
  const inputRefs = useRef<Array<HTMLInputElement | null>>([]);
  // 슬롯별 문자를 컴포넌트 내부 상태(고정 길이 6 배열)로 유지한다(#816 P2 후속) — 매 렌더마다
  // 압축된 value prop(join된 문자열)을 6칸으로 재분해하면, 중간 칸을 지워 join 결과가 짧아졌을 때
  // 그 압축 문자열을 되풀이해 슬롯에 다시 채우는 과정에서 뒤 칸 값들이 왼쪽으로 밀린다
  // (예: 'ABCDEF'에서 3번째 칸만 지우면 'ABDEF' → 재분해 시 D가 3번째 칸으로 당겨져 표시됨).
  // 빈 칸의 "위치"는 압축 문자열만으로 복원할 수 없으므로 위치 정보 자체를 상태로 들고 있는다.
  const [digits, setDigits] = useState<string[]>(() =>
    Array.from({ length: CODE_LENGTH }, (_, index) => value[index] ?? ''),
  );

  const commit = (next: string[]) => {
    setDigits(next);
    onChange(next.join(''));
  };

  const setDigitAt = (index: number, char: string) => {
    const next = digits.slice();
    next[index] = char;
    commit(next);
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

    const next = Array.from({ length: CODE_LENGTH }, (_, index) => pasted[index] ?? '');
    commit(next);
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
