type Props = {
  visible: boolean;
};

// 데모 모드 안내 배너(#1627, 백엔드 #1626 계약 GET /api/users/me의 isDemo=true) — 데모 계정은
// 매일 데이터가 초기화되므로, 세션 내내 사용자가 인지할 수 있도록 앱 셸 상단(사이드바+헤더 위,
// AppShellRoute.tsx)에 항상 고정 노출한다. visible=false(비데모 계정, 또는 isDemo 필드가 아직
// 내려오지 않는 환경에서 User.isDemo가 undefined인 경우 모두 포함)면 아무것도 렌더하지 않는다 —
// 이 컴포넌트 자체는 isDemo가 optional이라는 사실을 몰라도 되도록, 판정은 호출부(AppShellRoute)가
// authUser?.isDemo === true로 명시 평가해서 넘긴다(신규 optional 필드 방어는 여기 한곳에 집중).
export function DemoModeBanner({ visible }: Props) {
  if (!visible) return null;

  return (
    <div
      role="status"
      className="w-full bg-primary/10 px-4 py-2 text-center text-sm font-medium text-primary"
    >
      데모 모드 — 데이터는 매일 초기화됩니다
    </div>
  );
}
