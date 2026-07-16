// 등급 범례 — 지도 좌하단 오버레이. GRADE_COLOR/GRADE_LABEL을 순회해 구성(하드코딩 배열 금지)
import { GRADE_COLOR, GRADE_LABEL } from '../constants';

export function MapLegend() {
  return (
    <div className="absolute bottom-4 left-4 flex items-center gap-3 rounded-full border border-border bg-white/95 px-4 py-2 text-xs shadow-sm">
      {(Object.keys(GRADE_LABEL) as Array<keyof typeof GRADE_LABEL>).map((grade) => (
        <span key={grade} className="flex items-center gap-1.5">
          <span
            className="inline-block h-2.5 w-2.5 rounded-full"
            style={{ backgroundColor: GRADE_COLOR[grade] }}
          />
          {GRADE_LABEL[grade]}
        </span>
      ))}
    </div>
  );
}
