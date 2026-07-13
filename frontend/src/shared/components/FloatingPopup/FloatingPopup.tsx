import titleIcon from '../../../assets/brand/popup-title-icon.svg';
import closeIcon from '../../../assets/brand/popup-close-icon.svg';
import arrowIcon from '../../../assets/brand/popup-arrow-icon.svg';
import './FloatingPopup.css';

export interface FloatingPopupLink {
  label: string;
  onClick: () => void;
}

interface FloatingPopupProps {
  title?: string;
  onClose: () => void;
  links: FloatingPopupLink[];
  onConnectAgent?: () => void;
  waitingLabel?: string;
  /** 화면 우하단 고정 배치를 끄고 싶을 때(예: 데모/스토리북에서 프레임 안에 넣을 때) false로 설정 */
  fixedPosition?: boolean;
}

// Figma node-id 208-2460 "Floating Popup (Bottom-Right)" 기준 — BottomNavBarFab(node-id
// 208-2459) 클릭 시 그 위에 뜨는 빠른 링크 패널(챗봇 진입점). 두 컴포넌트가 Jira에서 같은
// 서브태스크(HAJA-138)로 묶여있어, 별도 래퍼 없이도 항상 FAB 바로 위(우하단)에 고정
// 배치되도록 fixedPosition 기본값을 true로 둠
export function FloatingPopup({
  title = 'HajaCheck 도우미',
  onClose,
  links,
  onConnectAgent,
  waitingLabel,
  fixedPosition = true,
}: FloatingPopupProps) {
  return (
    <div
      className={`floating-popup${fixedPosition ? ' floating-popup--fixed' : ''}`}
      role="dialog"
      aria-label={title}
    >
      <div className="floating-popup-header">
        <h3>
          <img className="floating-popup-title-icon" src={titleIcon} alt="" />
          {title}
        </h3>
        <button type="button" className="floating-popup-close" onClick={onClose} aria-label="닫기">
          <img src={closeIcon} alt="" />
        </button>
      </div>

      <div className="floating-popup-links">
        {links.map((link) => (
          <button
            key={link.label}
            type="button"
            className="floating-popup-link"
            onClick={link.onClick}
          >
            {link.label}
          </button>
        ))}
      </div>

      {(onConnectAgent || waitingLabel) && (
        <div className="floating-popup-footer">
          {onConnectAgent && (
            <button type="button" className="floating-popup-connect" onClick={onConnectAgent}>
              상담원 연결하기 <img src={arrowIcon} alt="" />
            </button>
          )}
          {waitingLabel && (
            <span className="floating-popup-waiting">
              <span className="floating-popup-waiting-dot" aria-hidden="true" />
              {waitingLabel}
            </span>
          )}
        </div>
      )}
    </div>
  );
}
