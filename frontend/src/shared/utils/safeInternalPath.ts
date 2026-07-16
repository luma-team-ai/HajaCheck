// 오픈 리다이렉트 방지 — 내부 절대경로만 허용(프로토콜-상대 //, 백슬래시 트릭 차단).
// ProtectedRoute가 state.from으로 보존한 복귀 경로를 LoginPage가 그대로 navigate에 넘기면,
// state.from에 외부 URL(//evil.com, /\evil.com 등)을 심어 로그인 후 외부 사이트로 리다이렉트시키는
// 공격이 가능하다(#280 P3). 반드시 '/'로 시작하고 '//'·'/\'로 시작하지 않는 내부 절대경로만 허용한다.
export function isSafeInternalPath(path: unknown): path is string {
  return (
    typeof path === 'string' &&
    path.startsWith('/') &&
    !path.startsWith('//') &&
    !path.startsWith('/\\')
  );
}
