import { useMutation } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import type { ApiError } from '../../../shared/api/types';
import { resolveRoleHomeRoute } from '../../../shared/constants/routes';
import { authApi } from '../api/authApi';
import { useInspectionStore } from '../../inspection/store/inspectionStore';
import { clearRagSessionId } from '../../support/utils/ragSessionId';
import { useAuthStore } from '../store/authStore';
import type { UserResponse } from '../types';

// 데모 계정으로 둘러보기(#1627, 백엔드 #1626 계약) — CompanyLoginTab 전용. 크레덴셜 입력 없이
// POST /api/auth/demo-login 한 번으로 데모 계정(기업 ADMIN) 세션을 발급받는다. 응답은 기존
// POST /api/auth/login과 동일한 UserResponse + 세션 쿠키이므로, 성공 처리(setUser·이전 세션
// 잔여 정리)는 useLogin.ts와 동일하게 맞춘다 — 공용 PC에서 데모 로그인 전 사용자의
// activeInspectionId/activeReportId/RAG session_id가 남아있으면 useLogin과 같은 크로스유저
// 노출 문제(#1194, #1590)가 이 진입점에서도 그대로 재현된다.
//
// 실패(404 비활성·429 rate limit 등)는 error로만 노출한다 — 문구·버튼 숨김 여부는 화면
// (CompanyLoginTab)이 error.status로 판단(메시지 매칭 금지, 이 프로젝트 컨벤션).
export function useDemoLogin() {
  const navigate = useNavigate();
  const setUser = useAuthStore((state) => state.setUser);

  const mutation = useMutation<UserResponse, ApiError, void>({
    mutationFn: () => authApi.demoLogin().then((res) => res.data),
    onSuccess: (user) => {
      const { clearActiveInspectionId, clearActiveReportId } = useInspectionStore.getState();
      clearActiveInspectionId();
      clearActiveReportId();
      clearRagSessionId();

      setUser(user);
      // 데모 진입은 폼 로그인과 달리 "원래 가려던 목적지"(state.from)가 있을 수 없는 신규
      // 진입이므로, 그 값을 살피지 않고 곧바로 role 홈(resolveRoleHomeRoute)으로 이동한다.
      navigate(resolveRoleHomeRoute(user.role));
    },
  });

  return {
    demoLogin: mutation.mutate,
    isPending: mutation.isPending,
    error: mutation.error,
    // 데모 로그인 기능이 서버에서 꺼져 있는 경우(404 계열, 계약 §1) — 재시도해도 계속 실패할
    // 뿐이므로 화면이 버튼을 숨길 수 있도록 판별값을 함께 내려준다.
    isUnavailable: mutation.error?.status === 404,
  };
}
