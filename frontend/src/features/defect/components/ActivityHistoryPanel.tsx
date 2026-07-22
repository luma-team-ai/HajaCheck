import { ErrorFallback } from '../../../shared/components/ErrorFallback';
import { useDefectRevisions } from '../hooks/useDefectRevisions';
import { DEFECT_STATUS_LABEL } from '../types';
import type { DefectStatus } from '../types';

type Props = {
  defectId: number;
};

function isDefectStatus(value: string): value is DefectStatus {
  return value in DEFECT_STATUS_LABEL;
}

// 상태값(enum 코드)을 사람이 읽을 라벨로 변환한다 — fieldChanged가 'status'가 아닌 값(향후 확장)이거나
// 값이 라벨 매핑에 없으면 원본 문자열을 그대로 보여준다(방어적 폴백, 화면이 깨지지 않도록).
function describeChange(fieldChanged: string, oldValue: string | null, newValue: string | null): string {
  if (fieldChanged === 'status' && oldValue && newValue && isDefectStatus(oldValue) && isDefectStatus(newValue)) {
    return `상태를 '${DEFECT_STATUS_LABEL[oldValue]}'에서 '${DEFECT_STATUS_LABEL[newValue]}'(으)로 변경했습니다.`;
  }
  return `${fieldChanged} 변경: ${oldValue ?? '-'} → ${newValue ?? '-'}`;
}

// 하자 상세 화면 활동 기록 타임라인(HAJA-314) — DefectExplainPanel과 동일하게 이 패널이 자체적으로
// 로딩/에러를 처리해, 활동 기록 조회 실패가 페이지의 다른 기능(이미지·상태 전이 등)을 막지 않게 한다.
export function ActivityHistoryPanel({ defectId }: Props) {
  const { data, isLoading, isError, refetch } = useDefectRevisions(defectId);

  return (
    <section className="defect-card defect-activity-panel">
      <h2>활동 기록</h2>

      {isLoading && (
        <p className="m-0 text-sm text-text-muted" role="status">
          불러오는 중...
        </p>
      )}

      {isError && <ErrorFallback message="활동 기록을 불러오지 못했습니다." onRetry={refetch} />}

      {!isLoading && !isError && data && data.content.length === 0 && (
        <p className="m-0 text-sm text-text-muted">아직 활동 기록이 없습니다.</p>
      )}

      {!isLoading && !isError && data && data.content.length > 0 && (
        <ol className="defect-activity-list">
          {data.content.map((revision) => (
            <li key={revision.id}>
              <span className="defect-activity-dot" aria-hidden="true" />
              <div>
                <div className="defect-activity-meta">
                  <time dateTime={revision.createdAt}>
                    {new Date(revision.createdAt).toLocaleString('ko-KR')}
                  </time>
                </div>
                <p>{describeChange(revision.fieldChanged, revision.oldValue, revision.newValue)}</p>
                {revision.reason && <p>사유: {revision.reason}</p>}
              </div>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
