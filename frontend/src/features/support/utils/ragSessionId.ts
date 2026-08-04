// RAG 챗봇 세션 ID localStorage 유틸(HAJA-668, #1548) — 설계 §2 확정: session_id는 React state가
// 아니라 localStorage에 저장해 새로고침·라우트 이동에도 대화가 이어지게 한다.
// features/auth/utils/savedLoginId.ts 패턴 그대로 — 프라이빗 모드 등 접근 실패는 조용히 무시한다.
const RAG_SESSION_ID_KEY = 'hajacheckRagSessionId';

export function getRagSessionId(): number | null {
  try {
    const raw = localStorage.getItem(RAG_SESSION_ID_KEY);
    if (raw === null) return null;
    const parsed = Number(raw);
    // 손상된 값(파싱 실패·NaN)으로 잘못된 API 호출을 방지 — null 취급하고 새 세션처럼 동작
    return Number.isFinite(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

export function setRagSessionId(id: number): void {
  try {
    localStorage.setItem(RAG_SESSION_ID_KEY, String(id));
  } catch {
    // 저장 실패 무시 — 세션 저장이 안 되어도 단발 질의 기능 자체엔 영향 없음
  }
}

export function clearRagSessionId(): void {
  try {
    localStorage.removeItem(RAG_SESSION_ID_KEY);
  } catch {
    // 삭제 실패 무시
  }
}
