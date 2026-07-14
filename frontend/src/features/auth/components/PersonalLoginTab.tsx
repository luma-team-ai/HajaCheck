import { AuthFooterLinks } from './AuthFooterLinks';
import { GOOGLE_OAUTH_PATH, KAKAO_OAUTH_PATH } from '../constants';

export function PersonalLoginTab() {
  const handleKakaoLogin = () => {
    window.location.href = KAKAO_OAUTH_PATH;
  };

  const handleGoogleLogin = () => {
    window.location.href = GOOGLE_OAUTH_PATH;
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
