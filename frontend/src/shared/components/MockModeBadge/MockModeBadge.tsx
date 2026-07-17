import { shouldEnableMocking } from '../../utils/shouldEnableMocking';

// MSW 목 모드 표시 배지 (#302) — 목이 켜져 있다는 사실을 화면에 드러낸다.
//
// 계기: oci-db 오버레이가 빠진 채 frontend-dev가 재생성되면 MSW가 기본값(켜짐)으로 동작하는데,
// 목 getMe가 항상 401이라 실 세션 쿠키를 가려 "로그인 성공 → /login 축출" 무한 루프가 났다.
// 서버는 인증에 성공하고 세션도 저장되므로 서버 로그엔 단서가 없어 원인 추적에 오래 걸렸다.
// 목 모드인지 알 방법이 콘솔뿐이었던 게 문제의 본질 — 조용한 실패를 시끄러운 실패로 바꾼다.
//
// 판정은 main.tsx의 worker.start() 게이팅과 동일한 shouldEnableMocking을 재사용한다
// (별도 조건을 두면 "목은 도는데 배지는 안 뜨는" 불일치가 생긴다).
// !DEV면 shouldEnableMocking이 false라 프로덕션 빌드에는 렌더되지 않는다.
// 한계: 이건 "목을 켜려는 의도"를 보는 것이라, worker.start()가 실패·타임아웃하면(main.tsx가
// 콘솔 error로 남김) 목이 안 도는데도 배지가 뜬다. 목이 도는데 안 뜨는 반대 방향(위험한 쪽)은
// 없으므로 이 방향의 오차는 감수한다.
export function MockModeBadge() {
  if (!shouldEnableMocking(import.meta.env)) return null;

  return (
    <div
      // 우하단은 ChatbotButton(FAB)이 쓰므로 좌하단. pointer-events-none 으로 클릭을 가리지 않는다.
      className="pointer-events-none fixed bottom-3 left-3 z-[9999] flex items-center gap-1.5 rounded-full bg-amber-500 px-2.5 py-1 text-xs font-semibold text-white shadow-md"
      role="status"
      title="MSW 목 서버가 요청을 가로채는 중입니다. 실 백엔드에 붙이려면 .env에 COMPOSE_FILE을 설정하거나 VITE_ENABLE_MSW=false로 끄세요(#302)."
    >
      <span className="h-1.5 w-1.5 rounded-full bg-white" aria-hidden="true" />
      MSW 목 모드
    </div>
  );
}
