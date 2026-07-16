import { useNavigate } from 'react-router-dom';
import { Button } from '../../../shared/components/Button';
import { COMPANY_SIGNUP_ROUTE, LOGIN_ROUTE } from '../constants';

// "기업 통합회원 가입"·"개인 회원가입" 진입점 — LoginHeroPanel(lg 이상, 브랜딩 패널 하단)과
// LoginPage의 모바일 전용 블록(lg 미만, 인증 패널 하단) 두 곳에서 공유한다(PR #297 P2 픽스).
// LoginHeroPanel이 `hidden lg:flex`라 그 안에만 있으면 1024px 미만에서 회원가입 진입 경로가
// 전혀 없어지는 회귀였다 — 핸들러·마크업을 여기 한 곳으로 승격해 두 렌더 지점이 동일 로직을 쓰게 한다.
export function AuthSignupCta() {
  const navigate = useNavigate();

  const handleCompanySignup = () => {
    navigate(COMPANY_SIGNUP_ROUTE);
  };

  // 개인 회원가입 전용 화면은 범위 외 — 소셜 로그인(개인회원 탭)으로 안내
  const handlePersonalSignup = () => {
    navigate(LOGIN_ROUTE);
  };

  return (
    <div className="flex w-full flex-col items-start gap-3">
      <Button variant="secondary" size="lg" className="w-full" onClick={handleCompanySignup}>
        기업 통합회원 가입
      </Button>
      <button
        type="button"
        className="cursor-pointer border-none bg-transparent p-0 text-[13px] text-text-muted underline"
        onClick={handlePersonalSignup}
      >
        개인 회원가입
      </button>
    </div>
  );
}
