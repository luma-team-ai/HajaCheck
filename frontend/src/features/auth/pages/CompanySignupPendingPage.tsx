import { Link } from 'react-router-dom';
import brandLogo from '../../../assets/brand/sidenav-brand-logo.png';
import { COMPANY_SIGNUP_ROUTE, LANDING_ROUTE, LOGIN_ROUTE } from '../constants';
import { getCompanySignupSession } from '../utils/companySignupSession';
import '../auth.css';

// 기업 가입 완료 화면(Figma 77-1236) — 승인 대기 단계 제거에 맞춰 스테퍼·상태 폴링 없이
// "가입이 완료되었어요" 확인 + 로그인 이동만 노출한다. 표시정보(상호명·신청 계정)는
// sessionStorage 세션에서 읽는다(URL에 opaque 토큰 노출 방지 — 기존 정책 유지).
export function CompanySignupPendingPage() {
  const session = getCompanySignupSession();

  if (!session) {
    return (
      <div className="auth-standalone-page">
        <section className="auth-standalone-panel">
          <p className="auth-form-error">잘못된 접근입니다. 회원가입을 다시 진행해 주세요.</p>
          <Link to={COMPANY_SIGNUP_ROUTE} className="auth-link-btn">
            회원가입으로
          </Link>
        </section>
      </div>
    );
  }

  const { companyName, maskedEmail } = session;

  return (
    <main className="relative flex min-h-screen w-full items-center justify-center bg-[#fafafa] px-6 py-16">
      <Link
        to={LANDING_ROUTE}
        className="absolute left-8 top-10 inline-flex items-center gap-2 rounded-sm text-sm text-[#47464b] transition-colors hover:text-zinc-900"
        aria-label="hajaCheck 홈으로"
      >
        <span aria-hidden="true">←</span>
        hajaCheck 홈으로
      </Link>

      <section
        aria-labelledby="signup-complete-title"
        className="relative w-full max-w-[440px] rounded-[20px] border border-white bg-white/70 p-8 shadow-[inset_0px_1px_0px_1px_#ffffff,0px_4px_24px_-4px_#0000000d] backdrop-blur-[10px]"
      >
        <header className="flex items-center justify-center">
          <Link to={LANDING_ROUTE} aria-label="HajaCheck 홈으로" className="inline-flex rounded-sm">
            <img src={brandLogo} alt="HajaCheck" className="h-7 w-auto object-contain" />
          </Link>
        </header>

        <div className="mt-8 flex justify-center" aria-hidden="true">
          <div className="flex h-32 w-32 items-center justify-center rounded-full bg-[#f4f1fb] ring-1 ring-[#e6e0e9]">
            <div className="flex h-20 w-20 items-center justify-center rounded-full bg-[#ece7f7] ring-1 ring-[#dcd4ee]">
              <span className="text-4xl">✅</span>
            </div>
          </div>
        </div>

        <h1
          id="signup-complete-title"
          className="mt-8 text-center text-[28px] font-medium leading-[36.4px] text-zinc-900"
        >
          가입이 완료되었어요
        </h1>

        <dl className="mt-8 flex flex-col gap-2 border border-zinc-200 bg-neutral-50 p-4 shadow-[inset_0px_1px_4px_1px_#00000005]">
          <div className="flex items-center justify-between">
            <dt className="text-[11px] font-medium text-zinc-700">상호명</dt>
            <dd className="text-[13px] font-medium text-zinc-900">{companyName}</dd>
          </div>
          <div className="h-px w-full bg-[#e6e0e9]" aria-hidden="true" />
          <div className="flex items-center justify-between">
            <dt className="text-[11px] font-medium text-zinc-700">신청 계정</dt>
            <dd className="font-mono text-xs text-[#7a7582]">{maskedEmail}</dd>
          </div>
        </dl>

        <Link
          to={LOGIN_ROUTE}
          className="mt-6 flex h-[50px] w-full items-center justify-center rounded-full bg-zinc-900 text-[15px] font-medium text-white transition-opacity hover:opacity-90"
        >
          로그인 화면으로
        </Link>
      </section>
    </main>
  );
}
