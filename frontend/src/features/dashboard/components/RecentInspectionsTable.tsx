import { useRecentInspections } from '../hooks/useRecentInspections';
import { StatusBadge } from './StatusBadge';

export function RecentInspectionsTable() {
  const { data, isLoading, isError } = useRecentInspections();

  return (
    <section className="dashboard-card recent-inspections-card">
      <div className="dashboard-card-header">
        <h3 className="dashboard-card-title">최근 점검</h3>
        <button type="button" className="dashboard-card-link">
          전체보기
        </button>
      </div>

      {isLoading && <p className="dashboard-card-status">불러오는 중...</p>}
      {isError && <p className="dashboard-card-status">최근 점검 목록을 불러오지 못했습니다.</p>}
      {!isLoading && !isError && (!data || data.length === 0) && (
        <p className="dashboard-card-status">최근 점검 이력이 없습니다.</p>
      )}

      {!isLoading && !isError && data && data.length > 0 && (
        <div className="table-scroll">
          <table className="recent-inspections-table">
            <thead>
              <tr>
                <th>시설물</th>
                <th>점검일</th>
                <th>담당자</th>
                <th>하자수</th>
                <th>상태</th>
              </tr>
            </thead>
            <tbody>
              {data.map((item) => (
                <tr key={item.id}>
                  <td>{item.facilityName}</td>
                  <td>{item.inspectedAt}</td>
                  <td>{item.inspector}</td>
                  <td>{item.defectCount}건</td>
                  <td>
                    <StatusBadge status={item.status} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
