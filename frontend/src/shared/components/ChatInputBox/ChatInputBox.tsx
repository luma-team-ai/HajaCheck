import { type FormEvent, useRef } from 'react';

type Props = {
  value: string;
  onChange: (value: string) => void;
  onSubmit: () => void;
  placeholder?: string;
  disabled?: boolean;
  'aria-label'?: string;
};

// 채팅 공통 입력 컴포넌트(#1000/#1001 후속) — AI어시스턴트(AiAssistantPage) 피그마 디자인(반투명 알약
// + 다크 원형 전송 버튼)을 상담 챗봇(고객측 ConversationPanel)·상담원 콘솔(CounselorChatWindow)에도
// 그대로 적용해 인풋박스 디자인을 통일한다. 각 화면이 개별로 폼+input+버튼을 구현하던 것을 대체.
export function ChatInputBox({
  value,
  onChange,
  onSubmit,
  placeholder,
  disabled = false,
  'aria-label': ariaLabel = '메시지 입력',
}: Props) {
  // IME(한글) 조합 중 Enter는 조합 확정용 — 전송과 구분하기 위한 플래그(AiAssistantPage와 동일 이유).
  const composingRef = useRef(false);

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (composingRef.current) return;
    if (value.trim() === '') return;
    onSubmit();
  }

  return (
    <form
      onSubmit={handleSubmit}
      className="flex w-full items-center gap-2 rounded-full border border-border bg-white/70 p-2 shadow-[0px_4px_6px_-4px_rgba(0,0,0,0.1)] backdrop-blur-[10px] transition-colors focus-within:border-point focus-within:ring-2 focus-within:ring-point/30"
    >
      <input
        type="text"
        value={value}
        onChange={(event) => onChange(event.target.value)}
        onCompositionStart={() => {
          composingRef.current = true;
        }}
        onCompositionEnd={() => {
          composingRef.current = false;
        }}
        placeholder={placeholder}
        aria-label={ariaLabel}
        disabled={disabled}
        className="min-w-0 flex-1 border-none bg-transparent px-4 py-2.5 text-base text-primary caret-point outline-none placeholder:text-text-muted focus:outline-none disabled:opacity-50"
      />
      <button
        type="submit"
        disabled={disabled || value.trim() === ''}
        aria-label="전송"
        className="inline-flex size-10 shrink-0 items-center justify-center rounded-full bg-primary disabled:opacity-50"
      >
        <svg
          width="16"
          height="16"
          viewBox="0 0 24 24"
          fill="none"
          stroke="#fff"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
        >
          <path d="M12 19V5M5 12l7-7 7 7" />
        </svg>
      </button>
    </form>
  );
}
