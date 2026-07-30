import type { AdminUser } from '../types';

type UserAvatarSize = 'sm' | 'lg';

const SIZE_CLASSES: Record<UserAvatarSize, string> = {
  sm: 'h-7 w-7 text-xs',
  lg: 'h-10 w-10 text-sm',
};

// 아바타 — 프로필 이미지가 있으면 이미지, 없으면 이름 첫 글자
export function UserAvatar({ user, size = 'sm' }: { user: AdminUser; size?: UserAvatarSize }) {
  if (user.avatarUrl) {
    return (
      <img
        className={`rounded-full object-cover ${SIZE_CLASSES[size]}`}
        src={user.avatarUrl}
        alt=""
      />
    );
  }

  return (
    <span
      className={`inline-flex items-center justify-center rounded-full bg-neutral-100 font-medium text-text-default ${SIZE_CLASSES[size]}`}
      aria-hidden
    >
      {user.name.slice(0, 1)}
    </span>
  );
}
