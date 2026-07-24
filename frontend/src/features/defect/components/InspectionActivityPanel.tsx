import { useInspectionActivity } from '../hooks/useInspectionActivity';
import { describeDefectChange } from '../utils/describeDefectChange';
import type { Defect } from '../types';

type Props = {
  defects: Defect[];
};

// 점검 상세(카드형) 우측 "활동 기록" 사이드바 — contract.md §화면 구조 ②. ActivityHistoryPanel(하자
// 단건)과 달리 점검에 속한 하자 전체의 활동을 모아 보여준다(useInspectionActivity 참고).
export function InspectionActivityPanel({ defects }: Props) {
  const { items, isLoading, isError } = useInspectionActivity(defects);

  return (
    <aside className="defect-card inspection-activity-panel" aria-label="점검 활동 기록">
      <h2>활동 기록</h2>

      {isLoading && (
        <p className="m-0 text-sm text-text-muted" role="status">
          불러오는 중...
        </p>
      )}

      {!isLoading && isError && (
        <p className="m-0 text-sm text-text-muted">활동 기록을 불러오지 못했습니다.</p>
      )}

      {!isLoading && !isError && items.length === 0 && (
        <p className="m-0 text-sm text-text-muted">아직 활동 기록이 없습니다.</p>
      )}

      {!isLoading && !isError && items.length > 0 && (
        <ol className="defect-activity-list">
          {items.map((item) => (
            <li key={item.id}>
              <span className="defect-activity-dot" aria-hidden="true" />
              <div>
                <div className="defect-activity-meta">
                  <span className="inspection-activity-panel__code">{item.defectCode}</span>
                  <time dateTime={item.createdAt}>{new Date(item.createdAt).toLocaleString('ko-KR')}</time>
                </div>
                <p>{describeDefectChange(item.fieldChanged, item.oldValue, item.newValue)}</p>
                {item.reason && <p>사유: {item.reason}</p>}
              </div>
            </li>
          ))}
        </ol>
      )}
    </aside>
  );
}
