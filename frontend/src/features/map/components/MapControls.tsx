// 지도 우상단 컨트롤 — 지도/위성 토글(HYBRID) + 확대/축소/내 위치 버튼
export type MapDisplayType = 'roadmap' | 'hybrid';

interface MapControlsProps {
  mapType: MapDisplayType;
  onChangeMapType: (mapType: MapDisplayType) => void;
  onZoomIn: () => void;
  onZoomOut: () => void;
  onMyLocation: () => void;
}

export function MapControls({
  mapType,
  onChangeMapType,
  onZoomIn,
  onZoomOut,
  onMyLocation,
}: MapControlsProps) {
  return (
    <div className="absolute right-4 top-4 z-10 flex flex-col items-center gap-2">
      {/* 지도 / 위성 토글 — 너비를 w-20(80px)으로 상하 컨트롤과 동일하게 통일 */}
      <div className="flex w-20 overflow-hidden rounded-full border border-border bg-white shadow-sm">
        {([
          { type: 'roadmap', label: '지도' },
          { type: 'hybrid', label: '위성' },
        ] as const).map(({ type, label }) => {
          const isActive = mapType === type;
          return (
            <button
              key={type}
              type="button"
              aria-pressed={isActive}
              onClick={() => onChangeMapType(type)}
              className={`w-10 py-1.5 text-center text-xs transition ${
                isActive
                  ? 'bg-primary font-semibold text-surface'
                  : 'font-medium text-text-muted hover:bg-surface-muted'
              }`}
            >
              {label}
            </button>
          );
        })}
      </div>

      {/* 확대 / 축소 버튼 — 너비를 w-20(80px)으로 지도/위성 버튼과 수평 폭 동기화 */}
      <div className="flex w-20 flex-col overflow-hidden rounded-xl border border-border bg-white shadow-sm">
        <button
          type="button"
          aria-label="지도 확대"
          onClick={onZoomIn}
          className="flex h-8 w-full items-center justify-center border-b border-border text-base font-semibold text-text-default enabled:hover:bg-surface-muted"
        >
          +
        </button>
        <button
          type="button"
          aria-label="지도 축소"
          onClick={onZoomOut}
          className="flex h-8 w-full items-center justify-center text-base font-semibold text-text-default enabled:hover:bg-surface-muted"
        >
          −
        </button>
      </div>

      {/* 내 위치 버튼 — 너비를 w-20(80px)으로 동기화하고 Figma 시안과 동일한 Crosshair/GPS SVG 적용 */}
      <button
        type="button"
        aria-label="내 위치로 이동"
        onClick={onMyLocation}
        className="flex h-8 w-20 items-center justify-center rounded-xl border border-border bg-white text-sm shadow-sm enabled:hover:bg-surface-muted"
      >
        <svg
          className="h-4 w-4 text-text-default"
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
          strokeWidth="2"
          aria-hidden="true"
        >
          <circle cx="12" cy="12" r="6" />
          <circle cx="12" cy="12" r="2" fill="currentColor" />
          <line x1="12" y1="2" x2="12" y2="5" strokeLinecap="round" />
          <line x1="12" y1="19" x2="12" y2="22" strokeLinecap="round" />
          <line x1="2" y1="12" x2="5" y2="12" strokeLinecap="round" />
          <line x1="19" y1="12" x2="22" y2="12" strokeLinecap="round" />
        </svg>
      </button>
    </div>
  );
}
