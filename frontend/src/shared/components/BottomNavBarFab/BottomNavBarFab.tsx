import fabIcon from '../../../assets/brand/support-fab-icon.svg';
import './BottomNavBarFab.css';

interface BottomNavBarFabProps {
  onClick: () => void;
  label?: string;
}

// Figma node-id 208-2459 "BottomNavBar (FAB)" 기준 — 화면 우하단 고정 플로팅 액션 버튼
export function BottomNavBarFab({ onClick, label = '고객지원 챗봇 열기' }: BottomNavBarFabProps) {
  return (
    <button type="button" className="bottom-nav-fab" onClick={onClick} aria-label={label}>
      <img className="bottom-nav-fab-icon" src={fabIcon} alt="" />
    </button>
  );
}
