import { useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { authApi } from '../api/authApi';
import { AUTH_ME_QUERY_KEY, LOGIN_ROUTE } from '../constants';
import { useAuthStore } from '../store/authStore';
import { useInspectionStore } from '../../inspection/store/inspectionStore';
import { clearInspectionCreateDraft } from '../../inspection/utils/inspectionCreateDraft';
import { clearDraftMediaFiles } from '../../inspection/utils/inspectionCreateDraftFiles';
import { clearRagSessionId } from '../../support/utils/ragSessionId';

// 로그아웃 — SideNavBar/Header가 공유하는 단일 훅 (React_코드_컨벤션.md §0 "공통 로직 중복 금지")
// logout API가 실패해도 클라이언트 세션(react-query 캐시·authStore)은 항상 정리한다 —
// 로그아웃은 사용자 관점에서 항상 성공해야 하는 액션이라, 네트워크 오류로 화면에 갇히면 안 된다.
// redirectTo(#535) — 플랫폼 관리자 콘솔(PlatformAdminShellRoute)은 로그아웃 후 기업회원 /login이
// 아니라 /platform-admin/login으로 돌아가야 한다. 기존 호출부(AppShellRoute 등)는 인자를 넘기지
// 않으므로 기본값 LOGIN_ROUTE로 기존 동작이 그대로 유지된다.
export function useLogout(redirectTo: string = LOGIN_ROUTE) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const clearUser = useAuthStore((state) => state.clearUser);
  const clearActiveInspectionId = useInspectionStore((state) => state.clearActiveInspectionId);
  const clearActiveReportId = useInspectionStore((state) => state.clearActiveReportId);

  const logout = async (): Promise<void> => {
    try {
      await authApi.logout();
    } catch {
      // 무시 — API 실패와 무관하게 클라이언트 세션은 정리한다
    } finally {
      // queryClient.clear()는 AuthGate가 상시 구독 중인 ['auth','me'] 쿼리 옵저버까지
      // 초기화해 재-pending 상태로 되돌리고(스플래시 재노출, PR #232 P2-C), react-query가
      // 그 재구독을 즉시 재요청으로 이어가 유효 쿠키가 남아있으면 세션이 재복원되는
      // 부작용(P2-D)이 있었다 — 그래서 auth 쿼리는 지우지 않고 settled-null로 고정만 하고,
      // 그 외 캐시만 제거한다.
      queryClient.removeQueries({ predicate: (query) => query.queryKey[0] !== 'auth' });
      // 진행 중이던 getMe(['auth','me']) 요청이 setQueryData(null) 이후 200으로 도착하면
      // 캐시를 사용자 값으로 덮어써 세션이 재복원된다(#280 P3) — settled-null로 고정하기 전에
      // in-flight 요청을 먼저 취소한다.
      await queryClient.cancelQueries({ queryKey: AUTH_ME_QUERY_KEY });
      queryClient.setQueryData(AUTH_ME_QUERY_KEY, null);
      clearUser();
      clearActiveInspectionId();
      clearActiveReportId();
      // 로그아웃 시 RAG 챗봇 세션(localStorage 영속)도 지운다(#1590) — 남겨두면 다음 사용자의
      // 첫 질의가 이전 사용자의 session_id로 나가 403으로 실패한다(#1194와 같은 계약).
      clearRagSessionId();
      // 점검 생성 폼의 임시저장(localStorage 텍스트 + IndexedDB 사진, #1703)도 지운다 — 텍스트
      // 초안이 sessionStorage에서 localStorage(TTL 7일)로 바뀌면서, 지우지 않으면 공유 PC에서
      // 로그아웃 후 최대 7일 안에 같은 브라우저로 로그인한 다른 사용자(다른 회사 포함)에게
      // 이전 사용자가 입력한 시설물·메모가 그대로 복원되는 정보 노출이 생긴다(P1, PR #1708 리뷰).
      // 이런 "화면 전용 로컬 초안" 유틸은 로그아웃 훅이 직접 import하지 않는 한 존재 자체가
      // 드러나지 않아 빠뜨리기 쉽다 — localStorage/IndexedDB에 사용자 입력·세션을 영속시키는
      // 유틸을 새로 만들 때는(clearRagSessionId처럼) 반드시 여기 정리 호출도 같이 추가할 것.
      clearInspectionCreateDraft();
      // clearDraftMediaFiles는 IndexedDB 접근이라 Promise를 반환하지만, 로그아웃 흐름(아래
      // navigate)을 그 완료까지 기다리게 하지 않는다. 구현 자체가 접근 실패를 내부에서 삼켜
      // 항상 resolve하므로(inspectionCreateDraftFiles.ts) reject로 인한 unhandled rejection
      // 걱정 없이 fire-and-forget(void)해도 안전하다 — InspectionCreatePage.tsx 제출 성공 시
      // 정리 흐름과 동일한 패턴.
      void clearDraftMediaFiles();
      navigate(redirectTo);
    }
  };

  return { logout };
}
