import personIcon from '../../../assets/brand/header-user-outlined.svg';
import companyDefaultIcon from '../../../assets/brand/sidenav-default-avatar.svg';
import planIcon from '../../../assets/brand/header-plan.svg';
import logoutIcon from '../../../assets/brand/sidenav-logout.svg';
import { useOutsideDismiss } from '../../hooks/useOutsideDismiss';

export interface ProfileMenuProps {
  /** 소속 기업명 — 개인 회원은 호출부가 "개인 회원" 등 폴백 문구를 넘긴다(mypage ProfileSection과 동일 정책) */
  companyName: string;
  /** 구독 플랜 표기(예: "Free") — mypage PLAN_NAME_LABEL과 동일 소스 사용 */
  planLabel: string;
  name: string;
  email: string;
  onMyInfoClick: () => void;
  onMyPlanClick: () => void;
  onLogout: () => void;
  /** 바깥 클릭·ESC 시 호출 — 열림 상태 자체는 Header가 소유(조건부 렌더링) */
  onClose?: () => void;
}

// Header 프로필 버튼 드롭다운(HAJA-758) — NotificationDropdown과 동일하게 순수 프리젠테이션 컴포넌트로 두고,
// 실제 사용자/플랜 데이터 조회는 호출부(app/AppShellRoute)가 책임진다.
export function ProfileMenu({
  companyName,
  planLabel,
  name,
  email,
  onMyInfoClick,
  onMyPlanClick,
  onLogout,
  onClose,
}: ProfileMenuProps) {
  const rootRef = useOutsideDismiss<HTMLDivElement>(onClose);

  return (
    <div
      ref={rootRef}
      className="flex w-64 flex-col overflow-hidden rounded-2xl border border-border bg-white shadow-2xl"
      role="menu"
      aria-label="프로필 메뉴"
    >
      <div className="flex items-center justify-between gap-2 px-5 pt-5 pb-4">
        <div className="flex min-w-0 items-center gap-2">
          <span className="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-full border border-border bg-[#ece6ee]">
            <img className="h-4 w-4" src={companyDefaultIcon} alt="" />
          </span>
          <span className="truncate text-sm font-semibold text-heading">{companyName}</span>
        </div>
        <span className="shrink-0 rounded-full border border-border px-2.5 py-0.5 text-xs text-text-muted">
          {planLabel}
        </span>
      </div>

      <div className="flex flex-col gap-0.5 border-t border-neutral-100/50 px-5 py-4">
        <span className="truncate text-sm font-medium text-heading">{name}</span>
        <span className="truncate text-xs text-text-muted">{email}</span>
      </div>

      <div className="flex flex-col border-t border-neutral-100/50 py-2">
        <button
          type="button"
          role="menuitem"
          className="flex cursor-pointer items-center gap-2.5 border-none bg-none px-5 py-2.5 text-left text-sm text-text-default hover:bg-surface-muted"
          onClick={onMyInfoClick}
        >
          <img className="h-4 w-4" src={personIcon} alt="" />
          내 정보
        </button>
        <button
          type="button"
          role="menuitem"
          className="flex cursor-pointer items-center gap-2.5 border-none bg-none px-5 py-2.5 text-left text-sm text-text-default hover:bg-surface-muted"
          onClick={onMyPlanClick}
        >
          <img className="h-4 w-4" src={planIcon} alt="" />
          내 플랜
        </button>
      </div>

      <div className="border-t border-neutral-100/50 py-2">
        <button
          type="button"
          role="menuitem"
          className="flex cursor-pointer items-center gap-2.5 border-none bg-none px-5 py-2.5 text-left text-sm text-text-default hover:bg-surface-muted"
          onClick={onLogout}
        >
          <img className="h-4 w-4" src={logoutIcon} alt="" />
          로그아웃
        </button>
      </div>
    </div>
  );
}
