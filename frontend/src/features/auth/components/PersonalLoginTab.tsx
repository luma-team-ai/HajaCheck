import { AuthFooterLinks } from './AuthFooterLinks';

export function PersonalLoginTab() {
  const handleKakaoLogin = () => {
    window.location.href = '/api/auth/oauth2/kakao';
  };

  const handleGoogleLogin = () => {
    window.location.href = '/api/auth/oauth2/google';
  };

  return (
    <div className="personal-login-tab">
      <p className="auth-panel-guide">소셜 계정으로 간편 로그인</p>

      <button
        type="button"
        className="social-login-btn social-login-btn--kakao"
        onClick={handleKakaoLogin}
      >
        카카오로 계속하기
      </button>
      <button
        type="button"
        className="social-login-btn social-login-btn--google"
        onClick={handleGoogleLogin}
      >
        Google로 계속하기
      </button>

      <AuthFooterLinks />
    </div>
  );
}
