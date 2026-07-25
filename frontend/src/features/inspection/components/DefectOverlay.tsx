import type { Defect, InspectionMedia } from '../types';

interface DefectOverlayProps {
  media: InspectionMedia;
  defects: Defect[];
  selectedId?: number;
  onSelect?: (id: number) => void;
}

// 등급별(A~E) 박스 색상 구분은 Figma 시안 반영으로 제거됨 — 이전 ponytail 임시 색상(GRADE_COLOR)을
// 확정 디자인(선택 시 마젠타 #d946ef 하이라이트)으로 교체 완료. 회귀 아님(#367 QA 확인).
export function DefectOverlay({ media, defects, selectedId, onSelect }: DefectOverlayProps) {
  return (
    // w-fit: 하자 박스는 이 div의 %로 위치를 잡으므로 div 크기가 렌더링된 이미지 크기와
    // 정확히 같아야 정렬이 맞는다. img를 자연 크기로 두고(w-full 강제 금지 — 썸네일 원본
    // 해상도보다 크게 늘리면 블러 발생, #781/#791) div가 그 크기로 shrink-wrap하게 한다.
    <div className="relative w-fit max-w-full">
      <img src={media.imageUrl} alt="점검 이미지" className="block max-w-full max-h-[60vh]" />
      {defects.map((defect) => {
        const isSelected = selectedId === defect.id;
        return (
          <button
            key={defect.id}
            type="button"
            onClick={() => onSelect?.(defect.id)}
            title={`${defect.type} · ${defect.grade}등급 · confidence ${Math.round(defect.confidence * 100)}%`}
            className={`absolute box-border cursor-pointer rounded-sm transition-all ${
              isSelected ? 'border-2 border-selection ring-2 ring-selection ring-opacity-30' : 'border-2 border-selection'
            }`}
            style={{
              left: `${defect.bbox.x * 100}%`,
              top: `${defect.bbox.y * 100}%`,
              width: `${defect.bbox.width * 100}%`,
              height: `${defect.bbox.height * 100}%`,
              backgroundColor: 'var(--color-selection-soft-bg)',
            }}
          >
            {isSelected && (
              <span className="absolute left-0 top-0 -translate-y-full whitespace-nowrap bg-selection px-[8px] py-[4px] text-[12px] font-semibold text-white">
                {defect.type} {defect.grade}등급
              </span>
            )}
          </button>
        );
      })}
    </div>
  );
}
