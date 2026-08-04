type Props = {
  text: string;
  // 'default' — 배정/연결 등 진행 안내(ChatBotPage의 기존 "상담원 연결 중입니다..." 알약과 동일 톤).
  // 'ended' — 상담 종료 안내. 디자인 톤은 유지하되(빨강 등 경고색 사용 안 함) 텍스트를 조금 더 진하게 해
  // "이 대화는 끝났다"는 구분을 준다.
  tone?: 'default' | 'ended';
};

// 상담 상태 동기화(#1506) — 대화 로그 흐름 안에 얹는 캡슐형 시스템 메시지. 고객 챗봇(ChatBotPage)의
// "상담원 연결 중입니다..." 알약 스타일을 그대로 재사용 가능한 공통 컴포넌트로 뽑아, "상담원과
// 연결되었습니다"/"상담이 종료되었습니다"(고객 화면), "고객이 상담을 종료했습니다"(상담원 화면)처럼
// 서버 이벤트로 상태가 바뀌는 순간을 대화 흐름 안에서 알려주는 용도로 양쪽 화면이 함께 쓴다.
export function ChatSystemMessage({ text, tone = 'default' }: Props) {
  return (
    <div className="flex justify-center">
      <div
        className={`rounded-full bg-surface-sunken px-4 py-2 text-xs font-medium ${
          tone === 'ended' ? 'text-primary' : 'text-text-muted'
        }`}
      >
        {text}
      </div>
    </div>
  );
}
