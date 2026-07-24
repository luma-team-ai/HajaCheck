import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import brandLogo from '../../../assets/brand/sidenav-brand-logo.png';
import type { ApiError } from '../../../shared/api/types';
import { Button } from '../../../shared/components/Button';
import { isSafeInternalPath } from '../../../shared/utils/safeInternalPath';
import { authApi } from '../api/authApi';
import { AuthSignupCta } from '../components/AuthSignupCta';
import { CompanyLoginTab } from '../components/CompanyLoginTab';
import { LoginHeroPanel } from '../components/LoginHeroPanel';
import { PersonalLoginTab } from '../components/PersonalLoginTab';
import { AUTH_ME_QUERY_KEY, AUTH_ME_QUERY_STALE_TIME_MS, LANDING_ROUTE } from '../constants';
import { useAuthStore } from '../store/authStore';
import type { UserResponse } from '../types';

type AuthTab = 'personal' | 'company';

export function LoginPage() {
  const [activeTab, setActiveTab] = useState<AuthTab>('personal');
  const navigate = useNavigate();
  const location = useLocation();
  const setUser = useAuthStore((state) => state.setUser);

  // CSRF 쿠키 프리밍 겸 세션 확인 — 서버 상태는 React Query로(React_코드_컨벤션.md §4)
  // AuthGate(app/AuthGate.tsx)와 동일 queryKey·staleTime 공유 — 로그아웃 직후 useLogout이
  // setQueryData(AUTH_ME_QUERY_KEY, null)로 고정한 값이 staleTime 동안 fresh로 간주돼,
  // /login으로 전환되며 이 컴포넌트가 마운트돼도 getMe를 즉시 재요청하지 않는다.
  // 즉시 재요청하면 로그아웃 API가 실패해 쿠키가 아직 유효한 경우 세션이 재복원된다(PR #232 P2-D).
  const {
    data: me,
    isSuccess,
    isError,
    error: sessionCheckError,
    refetch: retrySessionCheck,
  } = useQuery<UserResponse, ApiError>({
    queryKey: AUTH_ME_QUERY_KEY,
    queryFn: () => authApi.getMe().then((res) => res.data),
    retry: false,
    staleTime: AUTH_ME_QUERY_STALE_TIME_MS,
    // AuthGate(app/AuthGate.tsx)와 동일 옵션 — 로그아웃 API 실패로 쿠키가 아직 유효할 때,
    // staleTime 경과 후 탭 포커스 복귀/재연결로 이 쿼리가 재요청되면 200→setUser→navigate로
    // 세션이 재복원된다(#280 P2). 포커스/재연결 refetch를 꺼서 이 트리거를 없앤다.
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  });

  useEffect(() => {
    // 이미 로그인 상태면 원래 가려던 목적지(ProtectedRoute가 state.from으로 보존, P3-2)로 이동,
    // 없으면 기존대로 대시보드 — 네비게이션은 데이터 fetching이 아니라 부수효과이므로 useEffect 유지
    if (isSuccess && me) {
      setUser(me);
      const from = (location.state as { from?: string } | null)?.from;
      // state.from은 라우터 state에 실린 값이라 외부에서 임의로 주입 가능 — 내부 절대경로임을
      // 검증하지 않고 그대로 navigate에 넘기면 오픈 리다이렉트로 악용될 수 있다(#280 P3).
      navigate(isSafeInternalPath(from) ? from : '/dashboard');
    }
  }, [isSuccess, me, navigate, setUser, location.state]);

  // 401(미로그인)은 정상 흐름 — 로그인 폼을 그대로 노출한다.
  // 5xx/네트워크 오류는 로그인 여부를 알 수 없으므로, 로그인된 사용자에게 무단으로
  // 로그인 화면을 보여주는 대신 별도 에러 상태 + 재시도를 노출한다.
  const isSessionCheckUnavailable = isError && sessionCheckError.status !== 401;

  if (isSessionCheckUnavailable) {
    return (
      <div className="flex min-h-screen flex-col items-center justify-center gap-4 bg-surface-muted p-6 text-center">
        <p className="m-0 text-base text-text-default">
          로그인 상태를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.
        </p>
        <Button onClick={() => retrySessionCheck()}>다시 시도</Button>
      </div>
    );
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-surface-muted p-6">
      <div className="flex w-full max-w-[1080px] overflow-hidden rounded-[20px] border border-border bg-surface shadow-sm">
        <LoginHeroPanel />

        {/* justify-center로 탭+폼+하단링크 뭉텡이를 세로 중앙(상단보다 아래)에 두되, 탭 콘텐츠에
            min-h를 줘 개인(소셜)·기업(폼) 두 탭의 높이를 동일하게 맞춘다 — 높이 차로 블록이
            재중앙정렬돼 탭 위치가 흔들리던 문제를 막는다(#421, 최초 요청 "탭 위치 고정"). */}
        <section className="flex w-full flex-col justify-center gap-8 p-12 lg:w-1/2">
          {/* lg 미만(1024px 미만)에서는 LoginHeroPanel 전체가 hidden이라, 그 안에만 있는
              브랜드 로고(홈 진입점)가 화면에서 완전히 사라진다(#720, mobile-signup-cta와 동일
              원인·동일 패턴 — PR #297 P2 선례). 인증 패널 상단(탭 위)에 동일 로고를 별도로
              렌더하되 lg 이상에서는 숨겨 데스크톱 시안과 동일하게 유지한다(좌측 패널과 중복
              노출 없음). */}
          <div className="flex justify-center lg:hidden" data-testid="mobile-brand-logo">
            <Link to={LANDING_ROUTE} className="w-fit" aria-label="HajaCheck 홈으로">
              <img src={brandLogo} alt="HajaCheck" className="h-7 w-auto object-contain" />
            </Link>
          </div>

          {/* 개인/기업 탭 좌우 50/50 분할(#414, Figma #42) — 각 탭이 절반 폭을 차지하고 라벨 중앙정렬 */}
          <div className="flex border-b border-border" role="tablist">
            <button
              type="button"
              role="tab"
              aria-selected={activeTab === 'personal'}
              className={`-mb-px flex-1 cursor-pointer border-b-2 py-3 text-center text-base font-semibold ${
                activeTab === 'personal' ? 'border-primary text-heading' : 'border-transparent text-text-muted'
              }`}
              onClick={() => setActiveTab('personal')}
            >
              개인회원
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={activeTab === 'company'}
              className={`-mb-px flex-1 cursor-pointer border-b-2 py-3 text-center text-base font-semibold ${
                activeTab === 'company' ? 'border-primary text-heading' : 'border-transparent text-text-muted'
              }`}
              onClick={() => setActiveTab('company')}
            >
              기업회원
            </button>
          </div>

          {/* min-h로 두 탭 콘텐츠 높이를 통일(개인 182 / 기업 304 실측 → 기업 기준) — 탭 전환 시
              블록 높이가 고정돼 탭 위치가 흔들리지 않는다(#421). 하단 링크는 폼에 붙어 함께 내려간다. */}
          <div className="min-h-[304px]">
            {activeTab === 'personal' ? <PersonalLoginTab /> : <CompanyLoginTab />}
          </div>

          {/* lg 미만(1024px 미만)에서는 LoginHeroPanel 전체가 hidden이라, 그 안에만 있는
              "기업 통합회원 가입" 진입점이 화면에서 완전히 사라진다(PR #297 P2).
              동일 CTA를 인증 패널 하단에도 렌더하되 lg 이상에서는 숨겨 시안(데스크톱)과
              동일하게 유지한다 — 데스크톱은 LoginHeroPanel 쪽 CTA만 보임(중복 노출 없음). */}
          <div className="lg:hidden" data-testid="mobile-signup-cta">
            <AuthSignupCta />
          </div>
        </section>
      </div>
    </div>
  );
}
