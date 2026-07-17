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
    <div className="absolute right-4 top-4 z-10 flex flex-col items-end gap-2">
      <div className="flex overflow-hidden rounded-full border border-border bg-white shadow-sm">
        <button
          type="button"
          aria-pressed={mapType === 'roadmap'}
          onClick={() => onChangeMapType('roadmap')}
          className={
            mapType === 'roadmap'
              ? 'bg-primary px-3 py-1.5 text-xs font-semibold text-surface'
              : 'px-3 py-1.5 text-xs font-medium text-text-muted hover:bg-surface-muted'
          }
        >
          지도
        </button>
        <button
          type="button"
          aria-pressed={mapType === 'hybrid'}
          onClick={() => onChangeMapType('hybrid')}
          className={
            mapType === 'hybrid'
              ? 'bg-primary px-3 py-1.5 text-xs font-semibold text-surface'
              : 'px-3 py-1.5 text-xs font-medium text-text-muted hover:bg-surface-muted'
          }
        >
          위성
        </button>
      </div>

      <div className="flex flex-col overflow-hidden rounded-xl border border-border bg-white shadow-sm">
        <button
          type="button"
          aria-label="지도 확대"
          onClick={onZoomIn}
          className="h-8 w-8 border-b border-border text-base font-semibold text-text-default enabled:hover:bg-surface-muted"
        >
          +
        </button>
        <button
          type="button"
          aria-label="지도 축소"
          onClick={onZoomOut}
          className="h-8 w-8 text-base font-semibold text-text-default enabled:hover:bg-surface-muted"
        >
          −
        </button>
      </div>

      <button
        type="button"
        aria-label="내 위치로 이동"
        onClick={onMyLocation}
        className="flex h-8 w-8 items-center justify-center rounded-xl border border-border bg-white text-sm shadow-sm enabled:hover:bg-surface-muted"
      >
        ⦿
      </button>
    </div>
  );
}
