import fabIcon from '../../../assets/brand/support-fab-icon.svg';

interface BottomNavBarFabProps {
  onClick: () => void;
  label?: string;
}

// AppLayout이 mousedown 가드에서 이 버튼을 aria-label로 특정하므로(#474 재오픈 방지) 기본 라벨을
// 상수로 내보낸다. 문구를 바꿀 때 selector 쪽을 놓치면 가드가 조용히 풀려 팝업이 다시 열린다(#1715).
export const SUPPORT_FAB_LABEL = '상담 챗봇 열기';

// Figma node-id 208-2459 "BottomNavBar (FAB)" 기준 — 화면 우하단 고정 플로팅 액션 버튼
export function BottomNavBarFab({ onClick, label = SUPPORT_FAB_LABEL }: BottomNavBarFabProps) {
  return (
    <button
      type="button"
      className="fixed right-8 bottom-8 z-[900] inline-flex h-14 w-14 cursor-pointer items-center justify-center rounded-full border-none bg-primary shadow-lg"
      onClick={onClick}
      aria-label={label}
    >
      <img className="h-7 w-7" src={fabIcon} alt="" />
    </button>
  );
}
