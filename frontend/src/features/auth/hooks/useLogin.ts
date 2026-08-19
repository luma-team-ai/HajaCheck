import { useMutation } from '@tanstack/react-query';
import { useLocation, useNavigate } from 'react-router-dom';
import type { ApiError } from '../../../shared/api/types';
import { DASHBOARD_ROUTE } from '../../../shared/constants/routes';
import { isSafeInternalPath } from '../../../shared/utils/safeInternalPath';
import { authApi } from '../api/authApi';
import { clearPreviousUserLocalState } from '../../../shared/utils/clearPreviousUserLocalState';
import { useAuthStore } from '../store/authStore';
import type { LoginRequest, UserResponse } from '../types';

// 기업회원 로그인 탭(CompanyLoginTab) 전용 훅 — POST /api/auth/login.
//
// role 게이트는 서버가 한다(#1513, BE PR #1533). 이 엔드포인트는 ADMIN/INSPECTOR/USER만 허용하고,
// PLATFORM_ADMIN·COUNSELOR는 자격증명이 맞아도 세션을 발급받지 못한 채 403 AUTH_ROLE_NOT_ALLOWED로
// 거절된다. 따라서 onSuccess에 도달했다는 것 자체가 "허용 role + 세션 발급 완료"를 뜻한다 —
// 프론트가 role을 다시 판정하거나 발급된 세션을 logout()으로 되돌릴 일이 없다(되돌릴 세션이 없다).
// 거절은 error.code로 내려오므로 화면(CompanyLoginTab)이 문구로 안내한다.
export function useLogin() {
  const navigate = useNavigate();
  const location = useLocation();
  const setUser = useAuthStore((state) => state.setUser);

  const mutation = useMutation<UserResponse, ApiError, LoginRequest>({
    mutationFn: (body) => authApi.login(body).then((res) => res.data),
    onSuccess: (user) => {
      // 로그인 성공 시점에 이전 세션의 로컬 잔여 상태(activeInspectionId/activeReportId #1194,
      // RAG 세션 #1590, 점검 생성 폼 임시저장 #1703 등)를 지운다 — 공용 PC에서 다른 사용자가
      // 로그인해도 지우지 않으면 방금 로그인한 사용자에게 이전 사용자의 데이터가 그대로 노출된다
      // (PR머신 리뷰 P1). 로그인 3진입점·useLogout·401 강제 리다이렉트가 전부 같은 계약을
      // clearPreviousUserLocalState 하나로 지킨다(PR #1708 2차 P1 — 개별 복붙이 반복 누락의
      // 근본 원인이었다).
      clearPreviousUserLocalState();

      setUser(user);
      // ProtectedRoute(shared/components/ProtectedRoute.tsx)가 비로그인 접근 시 원래 목적지를
      // state.from으로 보존해 /login으로 보낸다. 이 폼 로그인 경로가 그 값을 무시하고 항상
      // '/dashboard'로 고정 이동해, 세션 만료 후 로그인한 사용자가 원래 보던 화면으로 돌아가지
      // 못하던 문제(#1442). state.from은 라우터 state에 실린 값이라 외부에서 임의로 주입 가능하므로,
      // LoginPage.tsx와 동일하게 isSafeInternalPath로 내부 절대경로임을 검증한 뒤에만 사용한다
      // (검증 없이 그대로 넘기면 오픈 리다이렉트로 악용될 수 있다 — #280 P3와 동일한 위험).
      // 여기까지 왔다면 서버가 허용한 기업 포털 role이 확정이므로 fallback은 대시보드 하나뿐이다
      // (#1513 — COUNSELOR 분기는 서버가 이 엔드포인트에서 403으로 막아 도달 불가라 제거).
      const from = (location.state as { from?: string } | null)?.from;
      navigate(isSafeInternalPath(from) ? from : DASHBOARD_ROUTE);
    },
  });

  return {
    login: mutation.mutate,
    isPending: mutation.isPending,
    error: mutation.error,
  };
}
