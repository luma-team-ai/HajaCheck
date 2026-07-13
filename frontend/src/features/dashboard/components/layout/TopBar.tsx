export function TopBar() {
  return (
    <header className="topbar">
      <nav className="topbar-breadcrumb" aria-label="현재 위치">
        <span>홈</span>
        <span className="topbar-breadcrumb-sep">/</span>
        <span className="topbar-breadcrumb-current">대시보드</span>
      </nav>

      <div className="topbar-actions">
        <button type="button" className="topbar-icon-btn" aria-label="알림">
          🔔
        </button>
        <span className="topbar-avatar" aria-label="내 프로필" role="img">
          👤
        </span>
      </div>
    </header>
  );
}
