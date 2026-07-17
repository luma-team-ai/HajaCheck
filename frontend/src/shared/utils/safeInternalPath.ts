// 오픈 리다이렉트 방지 — 내부(동일 오리진) 절대경로만 허용.
// ProtectedRoute가 state.from으로 보존한 복귀 경로를 LoginPage가 그대로 navigate에 넘기면,
// state.from에 외부 URL(//evil.com, /\evil.com 등)을 심어 로그인 후 외부 사이트로 리다이렉트시키는
// 공격이 가능하다(#280). 직접 접두 검사(startsWith)는 탭/개행·백슬래시 변형을 놓치므로,
// 브라우저의 스펙 준수 URL 파서로 정규화해 "'/'로 시작 + 동일 오리진으로 해석되는 경로"만 허용한다.
// (react-router는 cross-origin `to`에서 history.pushState 실패 시 window.location.assign으로
//  전체 페이지 이동을 폴백하므로, 이 가드가 유일한 방어선이라 정확성이 중요하다.)
export function isSafeInternalPath(path: unknown): path is string {
  if (typeof path !== 'string' || !path.startsWith('/')) return false;
  try {
    return new URL(path, window.location.origin).origin === window.location.origin;
  } catch {
    return false;
  }
}
