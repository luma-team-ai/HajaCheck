import titleIcon from '../../../assets/brand/popup-title-icon.svg';
import closeIcon from '../../../assets/brand/popup-close-icon.svg';
import arrowIcon from '../../../assets/brand/popup-arrow-icon.svg';

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
// Modal과 달리 바깥 클릭·ESC 닫기를 자체적으로 갖지 않음 — BottomNavBarFab을 소유한
// 상위 컴포넌트가 열림 상태와 바깥 클릭 처리를 함께 담당하는 설계(의도적)
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
      className={`flex w-90 flex-col gap-4 rounded-[20px] border border-border bg-white/90 p-[21px] shadow-[0px_20px_25px_-5px_rgba(0,0,0,0.1),0px_8px_10px_-6px_rgba(0,0,0,0.1)] backdrop-blur-[10px]${
        fixedPosition ? ' floating-popup--fixed fixed right-8 bottom-[100px] z-[950]' : ''
      }`}
      role="dialog"
      aria-label={title}
    >
      <div className="flex items-center justify-between">
        <h3 className="m-0 flex items-center gap-2 text-xl font-semibold text-primary">
          <img className="h-5 w-5" src={titleIcon} alt="" />
          {title}
        </h3>
        <button
          type="button"
          className="inline-flex cursor-pointer items-center justify-center border-none bg-none p-1"
          onClick={onClose}
          aria-label="닫기"
        >
          <img className="h-3.5 w-3.5" src={closeIcon} alt="" />
        </button>
      </div>

      <div className="flex flex-col gap-2">
        {links.map((link, index) => (
          <button
            // label 중복 가능성을 배제하기 위해 index를 함께 사용(Pagination의 dots-${index} 패턴과 동일)
            key={`${index}-${link.label}`}
            type="button"
            className="cursor-pointer rounded-full border border-border bg-surface px-[17px] py-[11px] text-left text-sm font-medium text-primary"
            onClick={link.onClick}
          >
            {link.label}
          </button>
        ))}
      </div>

      {(onConnectAgent || waitingLabel) && (
        <div className="flex items-center justify-between border-t border-border pt-[13px]">
          {onConnectAgent && (
            <button
              type="button"
              className="inline-flex cursor-pointer items-center gap-1 border-none bg-none p-0 text-sm text-text-muted"
              onClick={onConnectAgent}
            >
              상담원 연결하기 <img className="h-[9px] w-[9px]" src={arrowIcon} alt="" />
            </button>
          )}
          {waitingLabel && (
            <span className="inline-flex items-center gap-1.5 rounded-full border border-border bg-surface-muted px-[9px] py-[5px] text-xs text-text-default">
              <span className="h-1.5 w-1.5 rounded-full bg-info" aria-hidden="true" />
              {waitingLabel}
            </span>
          )}
        </div>
      )}
    </div>
  );
}
