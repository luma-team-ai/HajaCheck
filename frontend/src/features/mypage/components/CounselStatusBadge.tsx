import {
  COUNSEL_STATUS_BADGE_CLASS,
  COUNSEL_STATUS_LABEL,
  COUNSEL_WAITING_NUMBER_TEXT_CLASS,
} from '../statusClasses';
import type { CounselStatus } from '../types';

type Props = {
  status: CounselStatus;
  waitingNumber?: number | null;
};

// 내 상담 내역 테이블 "상태" 열 pill — InspectionRoleBadge와 동일한 pill 규격. 대기중(WAITING)일
// 때만 그 아래 '대기 순번 N' 서브텍스트를 덧붙인다(Figma 시안).
export function CounselStatusBadge({ status, waitingNumber }: Props) {
  return (
    <div className="flex flex-col items-start gap-0.5">
      <span
        className={`rounded-full px-2.5 py-1 text-xs font-semibold whitespace-nowrap ${COUNSEL_STATUS_BADGE_CLASS[status]}`}
      >
        {COUNSEL_STATUS_LABEL[status] ?? status}
      </span>
      {status === 'WAITING' && waitingNumber != null && (
        <span className={`text-xs ${COUNSEL_WAITING_NUMBER_TEXT_CLASS}`}>
          대기 순번 {waitingNumber}
        </span>
      )}
    </div>
  );
}
