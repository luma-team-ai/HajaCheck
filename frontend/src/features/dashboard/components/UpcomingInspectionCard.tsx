import { useNavigate } from 'react-router-dom';
import { DASHBOARD_COLOR_CLASS } from '../colors';
import { INSPECTION_NEW_PATH } from '../constants';
import type { UpcomingInspectionItem } from '../types';
import { deriveUpcomingInspectionStatusKind } from '../utils/upcomingInspectionStatus';

type Props = {
  item: UpcomingInspectionItem;
};

const BADGE_CLASS_BY_KIND = {
  overdue: DASHBOARD_COLOR_CLASS.upcomingOverdueBg,
  upcoming: DASHBOARD_COLOR_CLASS.upcomingSoonBg,
  grace: DASHBOARD_COLOR_CLASS.upcomingGraceBg,
  safe: DASHBOARD_COLOR_CLASS.upcomingSafeBg,
} as const;

// 시설물별 다음 점검일 카드 — D-DAY 원형 배지 + 시설물명 + "점검 생성" 이동(dev-03-02, #543)
export function UpcomingInspectionCard({ item }: Props) {
  const navigate = useNavigate();
  const kind = deriveUpcomingInspectionStatusKind(item.dDay);
  const dDayLabel = item.dDay === 0 ? 'D-DAY' : `D-${item.dDay}`;

  const handleCreateInspection = () => {
    navigate(`${INSPECTION_NEW_PATH}?facilityId=${item.facilityId}`);
  };

  return (
    <div
      className={`flex items-center justify-between gap-5 rounded-2xl border p-5 ${DASHBOARD_COLOR_CLASS.dividerBorder}`}
    >
      <div className="flex items-center gap-5 min-w-0">
        <div
          className={`flex h-16 w-16 shrink-0 flex-col items-center justify-center rounded-full ${BADGE_CLASS_BY_KIND[kind]}`}
          aria-label={`다음 점검일 ${dDayLabel}`}
        >
          <span aria-hidden="true" className="pb-0.5 text-xs font-semibold">
            D-DAY
          </span>
          <span aria-hidden="true" className="text-3xl font-bold leading-7">
            {item.dDay}
          </span>
        </div>
        <div className="flex flex-col gap-1 min-w-0">
          <p
            className={`m-0 overflow-hidden text-ellipsis whitespace-nowrap text-base font-medium ${DASHBOARD_COLOR_CLASS.bodyText}`}
          >
            {item.facilityName}
          </p>
          {item.inspectionCycleMonths !== null && (
            <p className={`m-0 text-sm ${DASHBOARD_COLOR_CLASS.mutedText}`}>
              {item.inspectionCycleMonths}개월 주기
            </p>
          )}
        </div>
      </div>
      <button
        type="button"
        className="shrink-0 cursor-pointer rounded-full bg-black px-4 py-1.5 text-sm font-medium text-white"
        onClick={handleCreateInspection}
      >
        점검 생성
      </button>
    </div>
  );
}
