import { GRADE_CLASSES, STATUS_PRESENTATION } from '../constants/defectPresentation';
import type { Defect } from '../types';

type Props = {
  defect: Defect;
  onSelect: (id: number) => void;
};

// GRADE_CLASSES/STATUS_PRESENTATION은 "border-* bg-* text-*" 필(pill) 배지용 클래스 묶음이다(신규
// 색상 상수 추가 금지 컨벤션 — defectPresentation.ts 재사용). 카드 그리드의 dot 배지는 border/bg 없이
// text-* 색상 클래스만 뽑아 dot의 background-color: currentColor로 재사용한다(Figma 정렬, #937).
// DefectCardGridControls.tsx의 상태 필터 탭 dot도 동일 로직이 필요해 export해서 재사용한다(#1005 —
// 로컬에 같은 함수 재정의 금지, 단일 진실 소스 유지).
export function pickTextColorClass(classNames: string): string {
  return classNames.split(' ').find((cls) => cls.startsWith('text-')) ?? '';
}

// 점검 상세(카드형, HAJA-393/394 §화면 구조 ②) 하자 카드 — contract.md 확정: 유형/등급뱃지/상태뱃지/
// 썸네일/AI신뢰도/최대폭. 등급·상태 배지 색상은 defectPresentation의 상수를 재사용(신규 색상 상수
// 추가 금지 컨벤션).
export function DefectCard({ defect, onSelect }: Props) {
  const statusPresentation = STATUS_PRESENTATION[defect.status];
  const gradeColorClass = defect.grade ? pickTextColorClass(GRADE_CLASSES[defect.grade]) : '';
  const statusColorClass = pickTextColorClass(statusPresentation.className);

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
            {defect.typeLabel?.slice(0, 1)}
          </span>
        )}
      </div>

      <div className="defect-card-grid__body">
        <div className="defect-card-grid__heading">
          <p className="defect-card-grid__type">{defect.typeLabel}</p>

          <div className="defect-card-grid__badges">
            {defect.grade && (
              <span className={`defect-card-grid__badge ${gradeColorClass}`}>
                <span className="defect-card-grid__badge-dot" aria-hidden="true" />
                {defect.grade}
              </span>
            )}
            <span className={`defect-card-grid__badge ${statusColorClass}`}>
              <span className="defect-card-grid__badge-dot" aria-hidden="true" />
              {statusPresentation.label}
            </span>
          </div>
        </div>

        <div className="defect-card-grid__stats">
          <span>AI 신뢰도 {Math.round(defect.confidence * 100)}%</span>
          <span className="defect-card-grid__stats-divider" aria-hidden="true" />
          <span>최대폭 {defect.crackWidthMm != null ? `${defect.crackWidthMm}mm` : '-'}</span>
        </div>
      </div>
    </button>
  );
}
