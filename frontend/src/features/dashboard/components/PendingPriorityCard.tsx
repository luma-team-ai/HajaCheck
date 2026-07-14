import { useNavigate } from 'react-router-dom';
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
    <section className="dashboard-card pending-priority-card">
      <h3 className="dashboard-card-title">처리 대기 (우선순위)</h3>

      {isLoading && <p className="dashboard-card-status">불러오는 중...</p>}
      {isError && <p className="dashboard-card-status">처리 대기 목록을 불러오지 못했습니다.</p>}
      {!isLoading && !isError && (!data || data.length === 0) && (
        <p className="dashboard-card-status">처리 대기 중인 하자가 없습니다.</p>
      )}

      {!isLoading && !isError && data && data.length > 0 && (
        <ul className="pending-priority-list">
          {data.map((item) => (
            <li key={item.id} className="pending-priority-item">
              <GradeBadge grade={item.grade} />
              <div className="pending-priority-info">
                <p className="pending-priority-title">{item.title}</p>
                <p className="pending-priority-meta">
                  {item.location} · {formatElapsedTime(item.occurredAt)}
                </p>
              </div>
              <button
                type="button"
                className="pending-priority-action"
                onClick={() => handleReview(item.id)}
              >
                검수하기
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
