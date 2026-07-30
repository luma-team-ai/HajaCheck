import { useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { DASHBOARD_COLOR_CLASS } from '../colors';
import { useRecentInspections } from '../hooks/useRecentInspections';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner';
import { StatusBadge } from './StatusBadge';

const RECENT_INSPECTIONS_FULL_LIST_PATH = '/dashboard/recent-inspections';

// Figma 원본 dev-mode 값 그대로(2026-07-25 재확인, 이전 라운드의 text-[9px]/text-[10px] 추측 전부 폐기):
// 헤더=text-xs(12px) uppercase tracking-wide + bg-pink-50, 본문=text-sm(14px).
const TH_BASE_CLASS =
  `text-left text-xs uppercase tracking-wide ${DASHBOARD_COLOR_CLASS.labelText} font-medium py-3 px-4 bg-pink-50 border-b border-[#eee] whitespace-nowrap`;
const TD_CLASS = 'p-3 text-sm border-b border-[#f4f4f4] whitespace-nowrap';

// 행 인터랙션(HAJA-17) — 클릭/Enter/Space로 선택, 키보드 포커스 가시화. 색은 colors.ts 단일 관리.
const ROW_BASE_CLASS = `cursor-pointer transition-colors ${DASHBOARD_COLOR_CLASS.rowFocusOutline}`;
// Figma 재대조(2026-07-24): zebra 회색 줄무늬(#fafbfc) 제거 — 미선택 행은 전부 흰색, hover에서만 강조.
const ROW_UNSELECTED_CLASS = DASHBOARD_COLOR_CLASS.rowHoverBg;
const ROW_SELECTED_CLASS = DASHBOARD_COLOR_CLASS.rowSelectedBg;

export function RecentInspectionsTable() {
  const navigate = useNavigate();
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
        {/* Figma 원본(2026-07-25): 카드 제목 text-xl(20px) — 공용 dashboard-card-title(15px)보다 큼 */}
        <h3 className="dashboard-card-title text-xl!">최근 점검</h3>
        {/* Figma 시안 대비 카드 우측 끝에 딱 붙지 않고 살짝 안쪽에 위치(#556) */}
        <button
          type="button"
          className="dashboard-card-link mr-2"
          onClick={() => navigate(RECENT_INSPECTIONS_FULL_LIST_PATH)}
        >
          전체보기
        </button>
      </div>

      {isLoading && <LoadingSpinner />}
      {isError && <p className="dashboard-card-status">최근 점검 목록을 불러오지 못했습니다.</p>}
      {!isLoading && !isError && (!data || data.length === 0) && (
        <p className="dashboard-card-status">최근 점검 이력이 없습니다.</p>
      )}

      {!isLoading && !isError && data && data.length > 0 && (
        <div className="overflow-x-auto">
          <table aria-label="최근 점검 목록" className="w-full border-collapse">
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
