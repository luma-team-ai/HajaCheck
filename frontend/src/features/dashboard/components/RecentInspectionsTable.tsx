import { useRecentInspections } from '../hooks/useRecentInspections';
import { StatusBadge } from './StatusBadge';

const TH_BASE_CLASS =
  'text-left text-[#888] font-semibold py-2.75 px-3 bg-[#f6f7f9] border-b border-[#eee] whitespace-nowrap';
const TD_CLASS = 'p-3 border-b border-[#f4f4f4] whitespace-nowrap';

export function RecentInspectionsTable() {
  const { data, isLoading, isError } = useRecentInspections();

  return (
    <section className="dashboard-card">
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
        <div className="overflow-x-auto">
          <table className="w-full border-collapse text-[13px]">
            <thead>
              <tr>
                <th className={`${TH_BASE_CLASS} rounded-tl-lg rounded-bl-lg`}>시설물</th>
                <th className={TH_BASE_CLASS}>점검일</th>
                <th className={TH_BASE_CLASS}>담당자</th>
                <th className={TH_BASE_CLASS}>하자수</th>
                <th className={`${TH_BASE_CLASS} rounded-tr-lg rounded-br-lg`}>상태</th>
              </tr>
            </thead>
            <tbody>
              {data.map((item) => (
                <tr key={item.id} className="even:bg-[#fafbfc]">
                  <td className={TD_CLASS}>{item.facilityName}</td>
                  <td className={TD_CLASS}>{item.inspectedAt}</td>
                  <td className={TD_CLASS}>{item.inspector}</td>
                  <td className={TD_CLASS}>{item.defectCount}건</td>
                  <td className={TD_CLASS}>
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
