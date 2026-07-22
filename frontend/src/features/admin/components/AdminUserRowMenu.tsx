import { useState } from 'react';
import type { ReactNode } from 'react';
import { useOutsideDismiss } from '../../../shared/hooks/useOutsideDismiss';
import type { AdminUser } from '../types';
import { MoreIcon } from './icons/MoreIcon';
import { ShieldIcon } from './icons/ShieldIcon';
import { StatusIcon } from './icons/StatusIcon';

export type AdminUserRowAction = 'CHANGE_ROLE' | 'CHANGE_STATUS';

interface AdminUserRowMenuProps {
  user: AdminUser;
  onAction: (action: AdminUserRowAction, user: AdminUser) => void;
}

// 행 우측 "⋯" 액션 메뉴 — Figma node-id 177-2017.
// 바깥 클릭·Escape로 닫히고, 열려 있는 동안 트리거에 aria-expanded를 노출한다.
export function AdminUserRowMenu({ user, onAction }: AdminUserRowMenuProps) {
  const [isOpen, setIsOpen] = useState(false);
  const containerRef = useOutsideDismiss<HTMLDivElement>(() => setIsOpen(false), isOpen);

  const menuItems: { action: AdminUserRowAction; label: string; icon: ReactNode }[] = [
    { action: 'CHANGE_ROLE', label: '역할 변경', icon: <ShieldIcon /> },
    { action: 'CHANGE_STATUS', label: '상태 변경', icon: <StatusIcon /> },
  ];

  return (
    <div className="relative flex justify-end" ref={containerRef}>
      <button
        type="button"
        className="inline-flex h-8 w-8 cursor-pointer items-center justify-center rounded-full border-none bg-none text-text-muted hover:bg-surface-muted hover:text-primary"
        onClick={() => setIsOpen((current) => !current)}
        aria-haspopup="menu"
        aria-expanded={isOpen}
        aria-label={`${user.name} 관리 메뉴`}
      >
        <MoreIcon />
      </button>

      {isOpen && (
        <div
          role="menu"
          className="absolute top-full right-0 z-20 mt-1 w-[160px] overflow-hidden rounded-2xl border border-border bg-surface py-1.5 shadow-[0px_10px_15px_-3px_rgba(0,0,0,0.1),0px_4px_6px_-4px_rgba(0,0,0,0.1)]"
        >
          {menuItems.map((item) => (
            <button
              key={item.action}
              type="button"
              role="menuitem"
              className="flex w-full cursor-pointer items-center gap-2.5 border-none bg-none px-4 py-2 text-left text-[13px] text-text-default hover:bg-surface-muted hover:text-primary"
              onClick={() => {
                setIsOpen(false);
                onAction(item.action, user);
              }}
            >
              <span className="text-text-muted" aria-hidden>
                {item.icon}
              </span>
              {item.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
