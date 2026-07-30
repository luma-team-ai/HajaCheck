import { useEffect, useState } from 'react';
import type { Defect, InspectionMedia } from '../types';

interface DefectOverlayProps {
  media: InspectionMedia;
  defects: Defect[];
  selectedId?: number;
  onSelect?: (id: number) => void;
  drawMode?: boolean;
  draggingBbox?: { x: number; y: number; width: number; height: number };
  onCanvasMouseDown?: (e: React.MouseEvent<HTMLDivElement>) => void;
  onCanvasMouseMove?: (e: React.MouseEvent<HTMLDivElement>) => void;
  onCanvasMouseUp?: (e: React.MouseEvent<HTMLDivElement>) => void;
}

// 등급별(A~E) 박스 색상 구분은 Figma 시안 반영으로 제거됨 — 이전 ponytail 임시 색상(GRADE_COLOR)을
// 확정 디자인(선택 시 마젠타 #d946ef 하이라이트)으로 교체 완료. 회귀 아님(#367 QA 확인).
export function DefectOverlay({
  media,
  defects,
  selectedId,
  onSelect,
  drawMode = false,
  draggingBbox,
  onCanvasMouseDown,
  onCanvasMouseMove,
  onCanvasMouseUp,
}: DefectOverlayProps) {
  // ponytail: 이미지 로드 실패(detail 503 등) 시 thumbnail로 폴백(#796)
  const [imgSrc, setImgSrc] = useState(media.imageUrl);

  // 이전/다음 이미지 네비게이션은 DefectOverlay를 리마운트하지 않고 media prop만 교체하므로
  // (ResultViewerPage에 key 없음), media가 바뀔 때 imgSrc를 재동기화해야 img가 새 이미지로
  // 갱신된다(P1 회귀, PR #978 리뷰).
  useEffect(() => {
    setImgSrc(media.imageUrl);
  }, [media.imageUrl]);

  const handleImageError = () => {
    // detail이 실패했으면 thumbnail로 대체 — 둘 다 실패하면 그대로 유지
    if (media.thumbnailUrl && imgSrc !== media.thumbnailUrl) {
      setImgSrc(media.thumbnailUrl);
    }
  };

  // ponytail: 겹친 박스 클릭 가능성을 위해 면적 내림차순 정렬 — 큰 박스가 먼저(아래), 작은 박스가 나중(위)에 그려짐
  const sortedDefects = defects.slice().sort((a, b) => {
    const areaA = a.bbox.width * a.bbox.height;
    const areaB = b.bbox.width * b.bbox.height;
    return areaB - areaA; // 내림차순
  });

  return (
    // w-fit: 하자 박스는 이 div의 %로 위치를 잡으므로 div 크기가 렌더링된 이미지 크기와
    // 정확히 같아야 정렬이 맞는다. img를 자연 크기로 두고(w-full 강제 금지 — 썸네일 원본
    // 해상도보다 크게 늘리면 블러 발생, #781/#791) div가 그 크기로 shrink-wrap하게 한다.
    <div
      className="relative w-fit max-w-full"
      onMouseDown={drawMode ? onCanvasMouseDown : undefined}
      onMouseMove={drawMode ? onCanvasMouseMove : undefined}
      onMouseUp={drawMode ? onCanvasMouseUp : undefined}
      onMouseLeave={drawMode ? onCanvasMouseUp : undefined}
    >
      <img
        src={imgSrc}
        alt="점검 이미지"
        onError={handleImageError}
        // 세로 사진이 60vh 높이 상한 때문에 양옆 여백이 과하게 크다는 실사용 피드백(#897) —
        // 79vh로 올려 여백을 줄임. 화면이 낮은 노트북에서는 진행률바·액션 버튼 행이 밀릴 여유가
        // 줄어드는 트레이드오프가 있음(60vh 대비 헤더/버튼용 여유가 40vh→21vh로 축소).
        className={`block max-w-full max-h-[79vh] ${drawMode ? 'cursor-crosshair' : ''}`}
      />
      {/* Existing defects */}
      {sortedDefects.map((defect) => {
        const isSelected = selectedId === defect.id;
        return (
          <button
            key={defect.id}
            type="button"
            onClick={() => !drawMode && onSelect?.(defect.id)}
            title={`${defect.type} · ${defect.grade}등급 · confidence ${Math.round(defect.confidence * 100)}%`}
            className={`absolute box-border ${drawMode ? 'cursor-default' : 'cursor-pointer'} rounded-sm transition-all ${
              isSelected ? 'border-2 border-selection ring-2 ring-selection ring-opacity-30' : 'border-2 border-selection'
            }`}
            disabled={drawMode}
            style={{
              left: `${defect.bbox.x * 100}%`,
              top: `${defect.bbox.y * 100}%`,
              width: `${defect.bbox.width * 100}%`,
              height: `${defect.bbox.height * 100}%`,
              backgroundColor: 'var(--color-selection-soft-bg)',
              pointerEvents: drawMode ? 'none' : 'auto',
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
      {/* Dragging bbox preview */}
      {drawMode && draggingBbox && (
        <div
          className="absolute border-2 border-dashed border-primary"
          style={{
            left: `${draggingBbox.x * 100}%`,
            top: `${draggingBbox.y * 100}%`,
            width: `${draggingBbox.width * 100}%`,
            height: `${draggingBbox.height * 100}%`,
            backgroundColor: 'rgba(59, 130, 246, 0.1)',
          }}
        />
      )}
    </div>
  );
}
