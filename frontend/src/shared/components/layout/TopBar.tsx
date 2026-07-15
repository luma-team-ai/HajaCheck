type Props = {
  // 상단바 breadcrumb 현재 위치 표시 — 페이지별로 다름(HAJA-185, 마이페이지 재사용 대비).
  // 미지정 시 기존 대시보드 동작 그대로 유지(하위 호환).
  currentLabel?: string;
};

export function TopBar({ currentLabel = '대시보드' }: Props) {
  return (
    <header className="topbar">
      <nav className="topbar-breadcrumb" aria-label="현재 위치">
        <span>홈</span>
        <span className="topbar-breadcrumb-sep">/</span>
        <span className="topbar-breadcrumb-current">{currentLabel}</span>
      </nav>

      <div className="topbar-actions">
        <button type="button" className="topbar-icon-btn" aria-label="알림">
          <svg
            width="20"
            height="20"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9" />
            <path d="M13.73 21a2 2 0 0 1-3.46 0" />
          </svg>
        </button>
        <span className="topbar-avatar" aria-label="내 프로필" role="img">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
            <path d="M12 12a5 5 0 1 0 0-10 5 5 0 0 0 0 10Zm0 2c-4.42 0-8 2.24-8 5v1h16v-1c0-2.76-3.58-5-8-5Z" />
          </svg>
        </span>
      </div>
    </header>
  );
}
