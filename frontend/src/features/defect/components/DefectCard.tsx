import { GRADE_CLASSES, STATUS_PRESENTATION } from '../constants/defectPresentation';
import type { DefectImageGroup, DefectStatus } from '../types';

type Props = {
  group: DefectImageGroup;
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
const MANAGED_STATUSES: Exclude<DefectStatus, 'DETECTED'>[] = ['CONFIRMED', 'IN_PROGRESS', 'RESOLVED'];

export function DefectCard({ group, onSelect }: Props) {
  const { representative } = group;
  const gradeColorClass = representative.grade
    ? pickTextColorClass(GRADE_CLASSES[representative.grade])
    : '';
  const typeLabels = Array.from(new Set(group.defects.map((defect) => defect.typeLabel)));
  const visibleTypes = typeLabels.slice(0, 2).join(' · ');
  const remainingTypeCount = Math.max(0, typeLabels.length - 2);
  const maxCrackWidth = group.defects.reduce<number | null>(
    (max, defect) =>
      defect.crackWidthMm == null ? max : max == null ? defect.crackWidthMm : Math.max(max, defect.crackWidthMm),
    null,
  );

  return (
    <button
      type="button"
      className="defect-card-grid__card"
      onClick={() => onSelect(representative.id)}
      aria-label={`${visibleTypes} 이미지 카드 상세 보기`}
    >
      <div className="defect-card-grid__thumb">
        {group.imageUrl ? (
          <img src={group.imageUrl} alt="" />
        ) : (
          <span className="defect-card-grid__thumb-empty" aria-hidden="true">
            사진 없음
          </span>
        )}
        <span className="defect-card-grid__count">하자 {group.defects.length}건</span>
      </div>

      <div className="defect-card-grid__body">
        <div className="defect-card-grid__heading">
          <p className="defect-card-grid__type">
            {visibleTypes}
            {remainingTypeCount > 0 && ` 외 ${remainingTypeCount}종`}
          </p>

          <div className="defect-card-grid__badges">
            {representative.grade && (
              <span className={`defect-card-grid__badge ${gradeColorClass}`}>
                <span className="defect-card-grid__badge-dot" aria-hidden="true" />
                {representative.grade}
              </span>
            )}
          </div>
        </div>

        <div className="defect-card-grid__status-summary" aria-label="상태별 하자 수">
          {MANAGED_STATUSES.map((status) => {
            const count = group.defects.filter((defect) => defect.status === status).length;
            if (count === 0) return null;
            const colorClass = pickTextColorClass(STATUS_PRESENTATION[status].className);
            return (
              <span key={status} className={colorClass}>
                <i aria-hidden="true" />
                {STATUS_PRESENTATION[status].label} {count}
              </span>
            );
          })}
        </div>

        <div className="defect-card-grid__stats">
          <span>최고 신뢰도 {Math.round(group.highestConfidence * 100)}%</span>
          <span className="defect-card-grid__stats-divider" aria-hidden="true" />
          <span>최대폭 {maxCrackWidth != null ? `${maxCrackWidth}mm` : '-'}</span>
        </div>
      </div>
    </button>
  );
}
