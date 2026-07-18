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
    <div className="relative w-full" style={{ maxWidth: media.width }}>
      <img src={media.imageUrl} alt="점검 이미지" className="block w-full" />
      {defects.map((defect) => {
        const isSelected = selectedId === defect.id;
        return (
          <button
            key={defect.id}
            type="button"
            onClick={() => onSelect?.(defect.id)}
            title={`${defect.type} · ${defect.grade}등급 · confidence ${Math.round(defect.confidence * 100)}%`}
            className={`absolute box-border cursor-pointer rounded-sm transition-all ${
              isSelected ? 'border-2 border-[#d946ef] ring-2 ring-[#d946ef] ring-opacity-30' : 'border-2 border-[#d946ef]'
            }`}
            style={{
              left: `${defect.bbox.x * 100}%`,
              top: `${defect.bbox.y * 100}%`,
              width: `${defect.bbox.width * 100}%`,
              height: `${defect.bbox.height * 100}%`,
              backgroundColor: 'rgba(217,70,239,0.1)',
            }}
          >
            {isSelected && (
              <span className="absolute left-0 top-0 -translate-y-full whitespace-nowrap bg-[#d946ef] px-[8px] py-[4px] text-[12px] font-semibold text-white">
                {defect.type} {defect.widthMm}mm
              </span>
            )}
          </button>
        );
      })}
    </div>
  );
}
