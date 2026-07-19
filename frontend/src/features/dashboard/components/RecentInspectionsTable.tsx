import { useRef, useState } from 'react';
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

  // 키보드 내비게이션(roving tabindex) — 행 그룹의 Tab 정지점을 1개로 유지하고 방향키로 이동.
  const rowCount = data?.length ?? 0;
  const [focusedIndex, setFocusedIndex] = useState(0);
  const rowRefs = useRef<(HTMLTableRowElement | null)[]>([]);
  // 리페치로 행 수가 줄면 focusedIndex가 범위를 벗어나 모든 행이 tabIndex=-1(도달 불가)이 되는 것을 방지 — 렌더 시 파생 클램프.
  const safeFocusedIndex = Math.min(focusedIndex, rowCount - 1);
  const focusRow = (index: number) => {
    const clamped = Math.max(0, Math.min(index, rowCount - 1));
    setFocusedIndex(clamped);
    rowRefs.current[clamped]?.focus();
  };
  const handleRowKeyDown = (e: React.KeyboardEvent<HTMLTableRowElement>, index: number, id: number) => {
    switch (e.key) {
      case 'ArrowDown':
        e.preventDefault();
        focusRow(index + 1);
        break;
      case 'ArrowUp':
        e.preventDefault();
        focusRow(index - 1);
        break;
      case 'Home':
        e.preventDefault();
        focusRow(0);
        break;
      case 'End':
        e.preventDefault();
        focusRow(rowCount - 1);
        break;
      case 'Enter':
      case ' ':
        e.preventDefault();
        toggleSelect(id);
        break;
      default:
        break;
    }
  };

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
          <table role="grid" aria-label="최근 점검 목록" className="w-full border-collapse text-[13px]">
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
              {data.map((item, index) => {
                const isSelected = selectedId === item.id;
                return (
                  <tr
                    key={item.id}
                    ref={(el) => {
                      rowRefs.current[index] = el;
                    }}
                    className={`${ROW_BASE_CLASS} ${
                      isSelected ? ROW_SELECTED_CLASS : ROW_UNSELECTED_CLASS
                    }`}
                    aria-selected={isSelected}
                    tabIndex={index === safeFocusedIndex ? 0 : -1}
                    onClick={() => {
                      setFocusedIndex(index);
                      toggleSelect(item.id);
                    }}
                    onKeyDown={(e) => handleRowKeyDown(e, index, item.id)}
                  >
                    <td
                      role="gridcell"
                      className={`${TD_CLASS}${
                        isSelected ? ` ${DASHBOARD_COLOR_CLASS.rowSelectedBar}` : ''
                      }`}
                    >
                      {item.facilityName}
                    </td>
                    <td role="gridcell" className={TD_CLASS}>{item.inspectedAt}</td>
                    <td role="gridcell" className={TD_CLASS}>{item.inspector}</td>
                    <td role="gridcell" className={TD_CLASS}>{item.defectCount}건</td>
                    <td role="gridcell" className={TD_CLASS}>
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
