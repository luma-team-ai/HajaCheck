import { shouldEnableMocking } from '../../utils/shouldEnableMocking';

// 개발 모드 표시 배지 (#302 → #311로 확장) — "지금 어떤 걸 보고 있는지"를 화면에 드러낸다.
//
// 계기: 로컬 스택엔 프론트가 둘이라(80=nginx dist / vite dev 서버) 지금 어느 쪽을 보는지 화면만
// 봐선 알 수 없었다. 게다가 dev 서버는 MSW가 기본 켜짐이라, 목 getMe(항상 401)가 실 세션을 가려
// "로그인 성공 → /login 축출" 무한 루프가 나도 서버 로그엔 단서가 없었다(#302).
// 조용한 실패를 시끄러운 실패로 바꾸는 게 목적이다.
//
// ┌ 렌더 규칙 ─────────────────────────────────────────────────────┐
// │ dev 빌드 + MSW 꺼짐 → "DEV"           (zinc, 차분)             │
// │ dev 빌드 + MSW 켜짐 → "DEV · MSW 목"  (amber, 경고)            │
// │ 프로덕션 빌드       → 렌더 안 함                                │
// └───────────────────────────────────────────────────────────────┘
//
// ⚠️ 프로덕션 빌드에서 "회색 배지"를 띄우는 선택지는 없다. nginx가 서빙하는 dist는 실제 운영에
// 배포되는 산출물과 동일해서, 클라이언트에서 "로컬 dist"와 "진짜 운영"을 구분할 수단이 없다.
// 회색이라도 렌더하면 운영 사이트에 그대로 노출된다. 그래서 **배지의 부재가 곧 "dist를 보고 있다"**
// 는 신호다(로컬에선 사진 모드, 운영에선 정상).
//
// MSW 판정은 main.tsx의 worker.start() 게이팅과 동일한 shouldEnableMocking을 재사용한다
// (별도 조건을 두면 "목은 도는데 배지는 안 뜨는" 불일치가 생긴다).
// 한계: 이건 "목을 켜려는 의도"를 보는 것이라, worker.start()가 실패·타임아웃하면(main.tsx가
// 콘솔 error로 남김) 목이 안 도는데도 MSW 표시가 뜬다. 목이 도는데 안 뜨는 반대 방향(위험한 쪽)은
// 없으므로 이 방향의 오차는 감수한다.
export function DevModeBadge() {
  if (!import.meta.env.DEV) return null;

  const mocking = shouldEnableMocking(import.meta.env);

  return (
    <div
      // 우하단은 ChatbotButton(FAB)이 쓰므로 좌하단. 단 좌하단엔 SideNavBar 로그아웃 버튼이 겹치므로
      // pointer-events-none 으로 클릭을 통과시키고, 작게·반투명으로 시각적 방해를 줄인다.
      // (pointer-events-none 이라 hover·title 툴팁은 동작하지 않는다 — 설명은 이 주석과 가이드에 둔다)
      className={`pointer-events-none fixed bottom-1 left-1 z-[9999] flex items-center gap-1 rounded-full px-2 py-0.5 text-[10px] font-semibold text-white opacity-70 shadow-sm ${
        mocking ? 'bg-amber-500' : 'bg-zinc-700'
      }`}
      role="status"
    >
      <span className="h-1.5 w-1.5 rounded-full bg-white" aria-hidden="true" />
      {mocking ? 'DEV · MSW 목' : 'DEV'}
    </div>
  );
}
