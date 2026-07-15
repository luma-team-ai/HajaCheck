// 로그인 후 앱 셸 우하단 고정 챗봇 진입 버튼(FAB). 스타일은 shared/styles/layout.css의 .chatbot-fab.
// (구 DashboardLayout에서 이관 — 앱 레이아웃 공통 요소라 AppLayout 하위로 이동, HAJA-186)
export function ChatbotButton() {
  return (
    <button type="button" className="chatbot-fab" aria-label="챗봇 열기">
      <svg
        width="24"
        height="24"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.8"
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
      >
        <path d="M21 11.5a8.38 8.38 0 0 1-8.5 8.5 8.5 8.5 0 0 1-3.8-.9L3 21l1.9-5.7a8.5 8.5 0 0 1-.9-3.8A8.38 8.38 0 0 1 12.5 3 8.38 8.38 0 0 1 21 11.5Z" />
      </svg>
    </button>
  );
}
