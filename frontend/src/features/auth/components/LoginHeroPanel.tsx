import { Link } from 'react-router-dom';
import brandLogo from '../../../assets/brand/sidenav-brand-logo.png';
import loginHeroImage from '../../../assets/brand/login-hero-illustration.svg';
import { AuthSignupCta } from './AuthSignupCta';
import { LANDING_ROUTE } from '../constants';

export function LoginHeroPanel() {
  // gap-8 — 콘텐츠가 패널 높이를 거의 채워 justify-between만으론 이미지와 하단 가입 CTA가 붙어버린다.
  // gap으로 최소 간격(32px)을 보장해 이미지 ↔ "기업 통합회원 가입" 사이 공백을 확보한다(#414, Figma #39).
  return (
    <section className="hidden w-1/2 flex-col justify-between gap-8 border-r border-border p-12 lg:flex">
      <div className="flex flex-col gap-6">
        {/* 통합 브랜드 로고(#429) — 클릭 시 랜딩(홈) 이동. w-fit으로 클릭 영역을 로고 폭으로 제한 */}
        <Link to={LANDING_ROUTE} className="w-fit" aria-label="HajaCheck 홈으로">
          <img src={brandLogo} alt="HajaCheck" className="h-7 w-auto object-contain" />
        </Link>
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

      <AuthSignupCta />
    </section>
  );
}
