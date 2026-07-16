import { useNavigate } from 'react-router-dom';
import brandMark from '../../../assets/brand/brand-mark.png';
import loginHeroImage from '../../../assets/brand/login-hero-illustration.svg';
import { Button } from '../../../shared/components/Button';
import { COMPANY_SIGNUP_ROUTE, LOGIN_ROUTE } from '../constants';

export function LoginHeroPanel() {
  const navigate = useNavigate();

  const handleCompanySignup = () => {
    navigate(COMPANY_SIGNUP_ROUTE);
  };

  // 개인 회원가입 전용 화면은 범위 외 — 소셜 로그인(개인회원 탭)으로 안내
  const handlePersonalSignup = () => {
    navigate(LOGIN_ROUTE);
  };

  return (
    <section className="hidden w-1/2 flex-col justify-between border-r border-border p-12 lg:flex">
      <div className="flex flex-col gap-6">
        <div className="flex items-center gap-2">
          <img src={brandMark} alt="" className="h-[25px] w-[25px] object-contain" />
          <span className="text-[15px] font-semibold text-heading">HajaCheck</span>
        </div>
        <h1 className="m-0 text-[32px] font-semibold leading-[41.6px] tracking-[-0.8px] text-heading">
          하나의 로그인으로
          <br />
          HajaCheck 서비스를
          <br />
          편하게 이용하세요.
        </h1>
      </div>

      {/* #e6e0e9는 tokens.css에 대응 토큰 없음(타 오너 자산 미터치) — mix-blend-multiply는 배경과
          곱연산되는 효과라 배경이 없으면(흰 배경) 블렌드가 사실상 무효화돼 시안 색감이 사라진다.
          실제로 필요한 배경이라 Figma 실측 hex를 그대로 사용(#292의 #1a9a52 선례와 동일 근거 표기). */}
      <div className="aspect-[4/3] w-full overflow-hidden rounded-2xl bg-[#e6e0e9]">
        <img
          src={loginHeroImage}
          className="h-full w-full object-cover mix-blend-multiply"
          alt=""
          aria-hidden="true"
        />
      </div>

      <div className="flex flex-col items-start gap-3">
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
    </section>
  );
}
