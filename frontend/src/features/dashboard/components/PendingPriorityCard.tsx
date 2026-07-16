import { useNavigate } from 'react-router-dom';
import { DASHBOARD_COLOR_CLASS } from '../colors';
import { inspectionReviewPath } from '../constants';
import { usePendingPriority } from '../hooks/usePendingPriority';
import { formatElapsedTime } from '../utils/formatElapsedTime';
import { GradeBadge } from './GradeBadge';

export function PendingPriorityCard() {
  const { data, isLoading, isError } = usePendingPriority();
  const navigate = useNavigate();

  // 스토리보드 DASH-01 A2: "검수하기" → 처리 대기 하자의 수동 검수 화면(FR-4-02)으로 이동
  const handleReview = (id: number) => {
    navigate(inspectionReviewPath(id));
  };

  return (
    <section className="dashboard-card">
      <div className="dashboard-card-header">
        <h3 className="dashboard-card-title">처리 대기</h3>
        <span className={`text-[13px] font-semibold ${DASHBOARD_COLOR_CLASS.mutedText}`}>우선순위</span>
      </div>

      {isLoading && <p className="dashboard-card-status">불러오는 중...</p>}
      {isError && <p className="dashboard-card-status">처리 대기 목록을 불러오지 못했습니다.</p>}
      {!isLoading && !isError && (!data || data.length === 0) && (
        <p className="dashboard-card-status">처리 대기 중인 하자가 없습니다.</p>
      )}

      {!isLoading && !isError && data && data.length > 0 && (
        <ul className="list-none m-0 p-0 flex flex-col gap-3">
          {data.map((item) => (
            <li
              key={item.id}
              className={`flex flex-col gap-2 py-3.5 px-4 border ${DASHBOARD_COLOR_CLASS.dividerBorder} rounded-[14px]`}
            >
              <div className="flex items-center justify-between gap-2">
                <div className="flex items-center gap-2 min-w-0">
                  <GradeBadge grade={item.grade} />
                  <p className="text-sm font-bold m-0 overflow-hidden text-ellipsis whitespace-nowrap">
                    {item.title}
                  </p>
                </div>
                <span className={`shrink-0 text-xs ${DASHBOARD_COLOR_CLASS.mutedText}`}>
                  {formatElapsedTime(item.occurredAt)}
                </span>
              </div>
              <p className="text-[13px] text-[#777] m-0 overflow-hidden text-ellipsis whitespace-nowrap">
                {item.location}
              </p>
              <div className="flex justify-end">
                <button
                  type="button"
                  className={`shrink-0 bg-white border border-[#d8dbe6] rounded-lg py-1.75 px-3.5 text-[13px] font-semibold ${DASHBOARD_COLOR_CLASS.bodyText} cursor-pointer`}
                  onClick={() => handleReview(item.id)}
                >
                  검수하기
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
