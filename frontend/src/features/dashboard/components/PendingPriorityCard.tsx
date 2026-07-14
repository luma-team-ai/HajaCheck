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
      <div className="dashboard-card-header">
        <h3 className="dashboard-card-title">처리 대기</h3>
        <span className="pending-priority-subtitle">우선순위</span>
      </div>

      {isLoading && <p className="dashboard-card-status">불러오는 중...</p>}
      {isError && <p className="dashboard-card-status">처리 대기 목록을 불러오지 못했습니다.</p>}
      {!isLoading && !isError && (!data || data.length === 0) && (
        <p className="dashboard-card-status">처리 대기 중인 하자가 없습니다.</p>
      )}

      {!isLoading && !isError && data && data.length > 0 && (
        <ul className="pending-priority-list">
          {data.map((item) => (
            <li key={item.id} className="pending-priority-item">
              <div className="pending-priority-top">
                <div className="pending-priority-title-group">
                  <GradeBadge grade={item.grade} />
                  <p className="pending-priority-title">{item.title}</p>
                </div>
                <span className="pending-priority-elapsed">{formatElapsedTime(item.occurredAt)}</span>
              </div>
              <p className="pending-priority-location">{item.location}</p>
              <div className="pending-priority-footer">
                <button
                  type="button"
                  className="pending-priority-action"
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
