export function LoginHeroPanel() {
  const handleComingSoon = () => {
    window.alert('준비 중인 기능입니다.');
  };

  return (
    <section className="login-hero-panel">
      <div className="login-hero-content">
        <span className="login-hero-logo">HajaCheck</span>
        <p className="login-hero-copy">
          하나의 로그인으로
          <br />
          HajaCheck 서비스를 편하게 이용하세요.
        </p>
      </div>

      {/* TODO: Figma 에셋 교체 — 건물 이미지 placeholder */}
      <div className="login-hero-image" aria-hidden="true" />

      <div className="login-hero-footer">
        <button type="button" className="login-hero-company-btn" onClick={handleComingSoon}>
          기업 통합회원 가입
        </button>
        <button type="button" className="login-hero-personal-link" onClick={handleComingSoon}>
          개인 회원가입
        </button>
      </div>
    </section>
  );
}
