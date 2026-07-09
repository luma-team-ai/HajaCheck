import type { Defect, InspectionMedia } from '../types';

interface DefectOverlayProps {
  media: InspectionMedia;
  defects: Defect[];
}

// ponytail: 등급별 색상 임시값 — 피그마 시안 확정되면 테마 토큰으로 교체
const GRADE_COLOR: Record<Defect['grade'], string> = {
  A: '#4caf50',
  B: '#8bc34a',
  C: '#ffc107',
  D: '#ff9800',
  E: '#f44336',
};

export function DefectOverlay({ media, defects }: DefectOverlayProps) {
  return (
    <div style={{ position: 'relative', width: '100%', maxWidth: media.width }}>
      <img src={media.imageUrl} alt="점검 이미지" style={{ width: '100%', display: 'block' }} />
      {defects.map((defect) => (
        <div
          key={defect.id}
          title={`${defect.type} · ${defect.grade}등급 · confidence ${Math.round(defect.confidence * 100)}%`}
          style={{
            position: 'absolute',
            left: `${defect.bbox.x * 100}%`,
            top: `${defect.bbox.y * 100}%`,
            width: `${defect.bbox.width * 100}%`,
            height: `${defect.bbox.height * 100}%`,
            border: `2px solid ${GRADE_COLOR[defect.grade]}`,
            boxSizing: 'border-box',
          }}
        />
      ))}
    </div>
  );
}
