import { useInspectionActivity } from '../hooks/useInspectionActivity';
import {
  describeDefectChange,
  getDefectRevisionStatusPresentation,
} from '../utils/describeDefectChange';
import { formatDefectActivityDateTime } from '../utils/defectFormat';

type Props = {
  defects: Array<{ id: number }>;
};

// 점검 상세(카드형) 우측 "활동 기록" 사이드바 — contract.md §화면 구조 ②. ActivityHistoryPanel(하자
// 단건)과 달리 점검에 속한 하자 전체의 활동을 모아 보여준다(useInspectionActivity 참고). 백엔드
// 페이지네이션은 없고(useInspectionActivity가 전체를 한 번에 반환), 목록 영역에서 스크롤한다.
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
        <ol className="defect-activity-list inspection-activity-panel__list">
          {items.map((item) => {
            const presentation =
              item.fieldChanged === 'status'
                ? getDefectRevisionStatusPresentation(item.newValue)
                : null;

            return (
              <li key={item.id}>
                <span className="defect-activity-dot" aria-hidden="true" />
                <div className="inspection-activity-panel__entry">
                  <div className="defect-activity-meta">
                    <span className="inspection-activity-panel__code">{item.defectCode}</span>
                    <time dateTime={item.createdAt}>
                      {formatDefectActivityDateTime(item.createdAt)}
                    </time>
                  </div>
                  {presentation && (
                    <span className={`defect-activity-status-badge ${presentation.className}`}>
                      <span aria-hidden="true" />
                      {presentation.label}
                    </span>
                  )}
                  <p>{describeDefectChange(item.fieldChanged, item.oldValue, item.newValue)}</p>
                  {item.reason && <p>사유: {item.reason}</p>}
                </div>
              </li>
            );
          })}
        </ol>
      )}
    </aside>
  );
}
