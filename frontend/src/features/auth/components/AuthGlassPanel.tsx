import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import brandLogo from '../../../assets/brand/sidenav-brand-logo.png';
import { LANDING_ROUTE } from '../constants';

type Props = {
  // 카드 안 <h1>의 id — section의 aria-labelledby로 연결한다. 화면마다 고유해야 한다.
  titleId: string;
  children: ReactNode;
};

// 로그인 밖 단독 인증 화면(초대 코드·아이디 찾기·비밀번호 찾기·새 비밀번호 설정)의 공용 셸(#906).
// 기준 시안은 Figma "hajaCheck 초대 코드 입력 페이지"이며, 그 마크업을 InviteCodePage(#799)에서
// 그대로 승격했다 — 셸을 화면마다 복사해두면 시안이 갱신될 때 4벌이 따로 놀기 시작한다.
//
// ⚠️ 홈으로 가는 링크가 두 개(좌상단 텍스트 · 카드 상단 로고)인 건 시안 그대로다. 접근성 이름을
// 각각 'hajaCheck 홈으로'(텍스트) / 'HajaCheck 홈으로'(로고)로 두어 대소문자로 구분한다 —
// InviteCodePage 테스트가 이 이름으로 두 링크를 가려내므로 표기를 바꾸지 말 것.
export function AuthGlassPanel({ titleId, children }: Props) {
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
        aria-labelledby={titleId}
        className="relative w-full max-w-[440px] rounded-[20px] border border-white bg-white/70 p-8 shadow-[inset_0px_1px_0px_1px_#ffffff,0px_4px_24px_-4px_#0000000d] backdrop-blur-[10px]"
      >
        <header className="flex items-center justify-center">
          <Link to={LANDING_ROUTE} aria-label="HajaCheck 홈으로" className="inline-flex rounded-sm">
            <img src={brandLogo} alt="HajaCheck" className="h-7 w-auto object-contain" />
          </Link>
        </header>

        {children}
      </section>
    </main>
  );
}
