type Props = {
  // 말풍선 정렬 — 상대방 발신은 좌측(고객 화면에서 상담원 타이핑), 상담원 화면에서 고객 타이핑도
  // 좌측 정렬(CounselorChatWindow의 비상담원 메시지와 동일 배치)이라 기본값 'start'로 충분.
  align?: 'start' | 'end';
};

// 상대방 "입력 중" 말풍선(#1000/#1001 후속) — AiAssistantPage의 AssistantTypingBubble과 동일한
// 점 3개 애니메이션을 공용화해 고객 채팅(ConversationPanel)·상담원 콘솔(CounselorChatWindow) 양쪽에서
// 재사용한다.
export function TypingIndicatorBubble({ align = 'start' }: Props) {
  return (
    <div className={`flex ${align === 'start' ? 'justify-start' : 'justify-end'}`}>
      <div className="flex items-center gap-1.5 rounded-2xl rounded-tl-sm border border-border bg-white px-5 py-4">
        <span className="sr-only">입력 중입니다...</span>
        {[0, 1, 2].map((i) => (
          <span
            key={i}
            className="size-2 animate-bounce rounded-full bg-text-muted"
            style={{ animationDelay: `${i * 0.15}s` }}
            aria-hidden="true"
          />
        ))}
      </div>
    </div>
  );
}
