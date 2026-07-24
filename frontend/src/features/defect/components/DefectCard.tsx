// defect-list-table__grade / defect-list-table__status 배지 클래스는 DefectListPage.css에 정의돼
// 있다 — 이 카드가 렌더링되는 InspectionDefectsPage는 그 CSS를 별도로 로드하지 않으므로 여기서
// 함께 임포트해 배지 스타일을 재사용한다(신규 색상/배지 스타일 중복 정의 금지).
import '../pages/DefectListPage.css';
import { GRADE_CLASSES, STATUS_PRESENTATION } from './DefectTable';
import type { Defect } from '../types';

type Props = {
  defect: Defect;
  onSelect: (id: number) => void;
};

// 점검 상세(카드형, HAJA-393/394 §화면 구조 ②) 하자 카드 — contract.md 확정: 유형/등급뱃지/상태뱃지/
// 썸네일/AI신뢰도/최대폭. 등급·상태 배지 색상은 DefectTable의 기존 상수를 재사용(신규 색상 상수
// 추가 금지 컨벤션).
export function DefectCard({ defect, onSelect }: Props) {
  const statusPresentation = STATUS_PRESENTATION[defect.status];

  return (
    <button
      type="button"
      className="defect-card-grid__card"
      onClick={() => onSelect(defect.id)}
      aria-label={`${defect.typeLabel} 하자 상세 보기`}
    >
      <div className="defect-card-grid__thumb">
        {defect.imageUrl ? (
          <img src={defect.imageUrl} alt="" />
        ) : (
          <span className="defect-card-grid__thumb-empty" aria-hidden="true">
            {defect.typeLabel.slice(0, 1)}
          </span>
        )}
      </div>

      <div className="defect-card-grid__body">
        <div className="defect-card-grid__badges">
          {defect.grade && (
            <span className={`defect-list-table__grade ${GRADE_CLASSES[defect.grade]}`}>{defect.grade}</span>
          )}
          <span className={`defect-list-table__status ${statusPresentation.className}`}>
            <span aria-hidden="true" />
            {statusPresentation.label}
          </span>
        </div>

        <p className="defect-card-grid__type">{defect.typeLabel}</p>

        <div className="defect-card-grid__stats">
          <span>AI 신뢰도 {Math.round(defect.confidence * 100)}%</span>
          <span>최대폭 {defect.crackWidthMm != null ? `${defect.crackWidthMm}mm` : '-'}</span>
        </div>
      </div>
    </button>
  );
}
