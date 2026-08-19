// 이전 사용자 잔여 로컬 상태 정리 — 공용 PC에서 계정이 바뀌는 6개 지점(로그인 3진입점
// useLogin/useDemoLogin/usePlatformAdminLogin·useCounselorLogin, useLogout, 401 강제 리다이렉트
// shared/api/axios.ts)이 전부 지켜야 하는 단일 계약이다.
//
// 원래 각 지점이 clearActiveInspectionId/clearActiveReportId(#1194)·clearRagSessionId(#1590) 호출을
// 개별적으로 복붙해 유지했는데, 점검 생성 폼 임시저장을 localStorage로 옮기며(#1703) useLogout에만
// clearInspectionCreateDraft/clearDraftMediaFiles를 추가했다가 나머지 5개 지점을 놓쳤다(PR #1708
// 2차 P1 리뷰). "6곳에 각자 복붙"이 반복 누락의 근본 원인이라, 여기 한 곳으로 모아 6개 지점이
// 전부 이 함수 하나만 호출하게 한다.
//
// ⚠️ localStorage·IndexedDB(또는 그 밖의 브라우저 영속 저장소)에 사용자 입력·세션을 저장하는
// 유틸을 새로 만들 때는 반드시 여기에도 정리 호출을 추가할 것 — 안 그러면 다음 사용자에게
// 이전 사용자의 데이터가 그대로 노출되는 크로스유저(회사 간 포함) 정보 노출로 이어진다.
import { useInspectionStore } from '../../features/inspection/store/inspectionStore';
import { clearInspectionCreateDraft } from '../../features/inspection/utils/inspectionCreateDraft';
import { clearDraftMediaFiles } from '../../features/inspection/utils/inspectionCreateDraftFiles';
import { clearRagSessionId } from '../../features/support/utils/ragSessionId';

export function clearPreviousUserLocalState(): void {
  // activeInspectionId/activeReportId — inspectionStore가 localStorage에 영속화되므로(#1194),
  // 공용 PC에서 계정이 바뀌어도 지우지 않으면 방금 로그인한(또는 남아있는) 사용자의 사이드바에
  // 이전 사용자의 회차 id가 그대로 노출된다.
  const { clearActiveInspectionId, clearActiveReportId } = useInspectionStore.getState();
  clearActiveInspectionId();
  clearActiveReportId();

  // RAG 챗봇 세션(localStorage 영속, #1590) — 남겨두면 다음 사용자의 첫 질의가 이전 사용자의
  // session_id로 나가 소유자 검증 403으로 실패한다.
  clearRagSessionId();

  // 점검 생성 폼 임시저장(#1703) — 텍스트 초안(localStorage, TTL 7일)과 첨부 사진 초안(IndexedDB)
  // 둘 다 지운다. 텍스트 초안이 sessionStorage(탭 종료 시 소멸)에서 localStorage로 바뀌며 노출
  // 창이 "탭을 닫을 때까지"에서 "최대 7일, 브라우저 재시작 후에도"로 크게 넓어졌다 — 지우지 않으면
  // 다음 로그인 사용자(다른 회사 포함)에게 이전 사용자가 입력한 시설물·메모가 그대로 복원된다.
  clearInspectionCreateDraft();
  // clearDraftMediaFiles는 IndexedDB 접근이라 Promise를 반환하지만, 호출부(로그인/로그아웃/401
  // 리다이렉트)의 진행을 그 완료까지 기다리게 하지 않는다. 구현 자체가 접근 실패를 내부에서 삼켜
  // 항상 resolve하므로(inspectionCreateDraftFiles.ts) reject로 인한 unhandled rejection 걱정 없이
  // fire-and-forget(void)해도 안전하다.
  void clearDraftMediaFiles();
}
