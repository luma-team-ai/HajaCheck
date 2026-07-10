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
        >
          {/* ponytail: 이미지 최상단 박스는 라벨이 잘릴 수 있음 — 피그마 시안 나오면 방향 자동전환으로 교체 */}
          <span
            style={{
              position: 'absolute',
              left: 0,
              top: 0,
              transform: 'translateY(-100%)',
              whiteSpace: 'nowrap',
              background: GRADE_COLOR[defect.grade],
              color: '#fff',
              fontSize: 12,
              padding: '2px 4px',
            }}
          >
            {defect.type} · {defect.grade}등급
          </span>
        </div>
      ))}
    </div>
  );
}
