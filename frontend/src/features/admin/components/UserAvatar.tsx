import type { AdminUser } from '../types';

// 아바타 — 프로필 이미지가 있으면 이미지, 없으면 이름 첫 글자
export function UserAvatar({ user }: { user: AdminUser }) {
  if (user.avatarUrl) {
    return <img className="h-7 w-7 rounded-full object-cover" src={user.avatarUrl} alt="" />;
  }

  return (
    <span
      className="inline-flex h-7 w-7 items-center justify-center rounded-full bg-neutral-100 text-xs font-medium text-text-default"
      aria-hidden
    >
      {user.name.slice(0, 1)}
    </span>
  );
}
