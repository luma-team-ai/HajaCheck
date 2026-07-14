// 개인/기업회원 탭 공통 하단 링크·배너 — 두 탭에서 동일하게 노출(스펙: "하단 링크·배너 동일")
export function AuthFooterLinks() {
  const handleComingSoon = () => {
    window.alert('준비 중인 기능입니다.');
  };

  return (
    <>
      <div className="auth-links-row">
        <button type="button" className="auth-link-btn" onClick={handleComingSoon}>
          아이디 찾기
        </button>
        <span className="auth-links-divider">|</span>
        <button type="button" className="auth-link-btn" onClick={handleComingSoon}>
          비밀번호 찾기
        </button>
      </div>
      <button
        type="button"
        className="auth-link-btn auth-link-btn--collaborator"
        onClick={handleComingSoon}
      >
        협업자 로그인
      </button>

      <div className="auth-workspace-banner">
        <p className="auth-workspace-banner-title">결함 검수의 모든 과정을 한 곳에서</p>
        <button type="button" className="auth-workspace-banner-link" onClick={handleComingSoon}>
          HajaCheck 워크스페이스 둘러보기 →
        </button>
      </div>
    </>
  );
}
