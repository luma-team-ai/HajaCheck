type Props = {
  visible: boolean;
};

// 하단 플로팅 경고 배너(#712 Figma 리디자인) — 월 분석 사용량이 80% 이상일 때만 화면 하단
// 중앙에 고정 노출한다. shared/components/FloatingPopup과 동일하게 fixed+z-index 고정 배치
// 패턴을 따르되, 마이페이지 전용 문구·조건이라 별도 shared 승격 없이 feature 로컬 컴포넌트로 둔다.
export function UsageLimitBanner({ visible }: Props) {
  if (!visible) return null;

  return (
    <div
      className="pointer-events-none fixed inset-x-0 bottom-6 z-[900] flex justify-center px-4"
      role="status"
    >
      <div className="pointer-events-auto inline-flex items-center gap-2 rounded-full bg-amber-500 px-5 py-2.5 text-sm font-semibold text-surface shadow-lg">
        이번 달 분석 한도에 근접했습니다 · 업그레이드 후 계속 이용하세요
      </div>
    </div>
  );
}
