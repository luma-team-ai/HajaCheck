import { useState } from 'react';
import { DASHBOARD_COLOR_CLASS } from '../colors';
import { useRecentInspections } from '../hooks/useRecentInspections';
import { StatusBadge } from './StatusBadge';

const TH_BASE_CLASS =
  `text-left ${DASHBOARD_COLOR_CLASS.labelText} font-semibold py-2.75 px-3 bg-[#f6f7f9] border-b border-[#eee] whitespace-nowrap`;
const TD_CLASS = 'p-3 border-b border-[#f4f4f4] whitespace-nowrap';

// 행 인터랙션(HAJA-17) — 클릭/Enter/Space로 선택, 키보드 포커스 가시화. 색은 colors.ts 단일 관리.
const ROW_BASE_CLASS = `cursor-pointer transition-colors ${DASHBOARD_COLOR_CLASS.rowFocusOutline}`;
// 미선택 행: zebra 줄무늬 + hover 강조. 선택 행: rose 배경(줄무늬 미적용 — even:bg가 nth-child 특이도로 이겨서 제외).
const ROW_UNSELECTED_CLASS = `even:bg-[#fafbfc] ${DASHBOARD_COLOR_CLASS.rowHoverBg}`;
const ROW_SELECTED_CLASS = DASHBOARD_COLOR_CLASS.rowSelectedBg;

export function RecentInspectionsTable() {
  const { data, isLoading, isError } = useRecentInspections();
  // 선택된 행 id — 같은 행을 다시 선택하면 해제(토글)
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const toggleSelect = (id: number) => setSelectedId((prev) => (prev === id ? null : id));

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
              {data.map((item) => {
                const isSelected = selectedId === item.id;
                return (
                  <tr
                    key={item.id}
                    className={`${ROW_BASE_CLASS} ${
                      isSelected ? ROW_SELECTED_CLASS : ROW_UNSELECTED_CLASS
                    }`}
                    aria-selected={isSelected}
                    tabIndex={0}
                    onClick={() => toggleSelect(item.id)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault();
                        toggleSelect(item.id);
                      }
                    }}
                  >
                    <td
                      className={`${TD_CLASS}${
                        isSelected ? ` ${DASHBOARD_COLOR_CLASS.rowSelectedBar}` : ''
                      }`}
                    >
                      {item.facilityName}
                    </td>
                    <td className={TD_CLASS}>{item.inspectedAt}</td>
                    <td className={TD_CLASS}>{item.inspector}</td>
                    <td className={TD_CLASS}>{item.defectCount}건</td>
                    <td className={TD_CLASS}>
                      <StatusBadge status={item.status} />
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
