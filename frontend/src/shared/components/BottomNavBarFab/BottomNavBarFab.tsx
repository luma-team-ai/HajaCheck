import fabIcon from '../../../assets/brand/support-fab-icon.svg';

interface BottomNavBarFabProps {
  onClick: () => void;
  label?: string;
}

// Figma node-id 208-2459 "BottomNavBar (FAB)" 기준 — 화면 우하단 고정 플로팅 액션 버튼
export function BottomNavBarFab({ onClick, label = '고객지원 챗봇 열기' }: BottomNavBarFabProps) {
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
