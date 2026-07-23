import { INSPECTION_ROLE_BADGE_CLASS, INSPECTION_ROLE_LABEL } from '../statusClasses';
import type { InspectionHistoryRole } from '../types';

type Props = {
  role: InspectionHistoryRole;
};

// 내 점검 이력 테이블 "내 역할" 열 배지 — SeatsSection의 역할 배지(pill)와 동일 규격/팔레트를 재사용.
export function InspectionRoleBadge({ role }: Props) {
  return (
    <span
      className={`rounded-full px-2.5 py-1 text-xs font-semibold whitespace-nowrap ${INSPECTION_ROLE_BADGE_CLASS[role]}`}
    >
      {INSPECTION_ROLE_LABEL[role] ?? role}
    </span>
  );
}
