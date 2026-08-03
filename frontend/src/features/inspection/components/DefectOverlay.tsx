import { useEffect, useState } from 'react';
import type { Defect, InspectionMedia } from '../types';
import { isDrawableBbox } from '../utils/isDrawableBbox';

// 등급 미판정(grade=null) 표기 — 라벨·title 어디서도 "null등급"이 노출되지 않게 한 곳에서 변환한다.
function gradeLabel(grade: Defect['grade']): string {
  return grade == null ? '등급 미판정' : `${grade}등급`;
}

// 박스 색상(팀 QA 요청 — 페이즈4의 등급별 색상은 원복). 미확정=마젠타(기존 선택 마커색),
// 검수 확정=초록.
const CONFIRMED_COLOR = '#16A34A';
const UNCONFIRMED_COLOR = '#D946EF';

function colorForDefect(defect: Defect): string {
  return defect.status !== 'DETECTED' ? CONFIRMED_COLOR : UNCONFIRMED_COLOR;
}

interface DefectOverlayProps {
  media: InspectionMedia;
  defects: Defect[];
  selectedId?: number;
  onSelect?: (id: number) => void;
  drawMode?: boolean;
  draggingBbox?: { x: number; y: number; width: number; height: number };
  /** 그리기 시작만 이 div에서 받는다 — 이동/종료는 부모가 window 레벨에서 직접 듣는다(팀 QA
   * 요청: 커서가 이미지 밖으로 나가도 드래그가 끊기지 않아야 함, ResultViewerPage 참고). */
  onCanvasMouseDown?: (e: React.MouseEvent<HTMLDivElement>) => void;
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
}: DefectOverlayProps) {
  // ponytail: 이미지 로드 실패(detail 503 등) 시 thumbnail로 폴백(#796)
  const [imgSrc, setImgSrc] = useState(media.imageUrl);
  // 상세 이미지는 커서 로딩이 걸리는 동안 이전 사진 위에 새 이미지의 박스(defects prop)가 먼저
  // 그려져 "엉뚱한 사진에 박스"로 보였다(팀 QA 발견). 완전히 막을 순 없지만(네트워크 시간은
  // 실재함 — ResultViewerPage의 인접 이미지 프리페치가 대부분의 경우를 이미 캐시로 가려준다),
  // 못 가린 나머지 구간엔 최소한 "로딩 중"임을 보여준다 — 흐리게 + 스피너.
  const [isLoaded, setIsLoaded] = useState(false);

  // 이전/다음 이미지 네비게이션은 DefectOverlay를 리마운트하지 않고 media prop만 교체하므로
  // (ResultViewerPage에 key 없음), media가 바뀔 때 imgSrc를 재동기화해야 img가 새 이미지로
  // 갱신된다(P1 회귀, PR #978 리뷰).
  useEffect(() => {
    setImgSrc(media.imageUrl);
    setIsLoaded(false);
  }, [media.imageUrl]);

  const handleImageError = () => {
    // detail이 실패했으면 thumbnail로 대체 — 둘 다 실패하면 그대로 유지
    if (media.thumbnailUrl && imgSrc !== media.thumbnailUrl) {
      setImgSrc(media.thumbnailUrl);
    }
  };

  // bbox가 없는 하자("박스 없이 계속"으로 추가했거나 AI가 좌표 없이 내려준 경우)는 이미지 위에
  // 그릴 좌표가 없다. 예전엔 그대로 그려서 `null * 100 = 0` → 0×0 픽셀 버튼이 됐고, 이 화면의
  // 유일한 선택 수단이 박스 클릭이라 영영 선택할 수 없었다(#1395). 이미지 아래 칩으로 분리해
  // 선택 경로를 준다 — 없는 위치를 임의로 지어내지 않으면서 검수는 가능하게.
  const drawableDefects = defects.filter(isDrawableBbox);
  const unplacedDefects = defects.filter((defect) => !isDrawableBbox(defect));

  // ponytail: 겹친 박스 클릭 가능성을 위해 면적 내림차순 정렬 — 큰 박스가 먼저(아래), 작은 박스가 나중(위)에 그려짐
  const sortedDefects = drawableDefects.slice().sort((a, b) => {
    const areaA = a.bbox.width * a.bbox.height;
    const areaB = b.bbox.width * b.bbox.height;
    return areaB - areaA; // 내림차순
  });

  return (
    // 위치 미지정 칩 행은 이미지 바깥(아래)에 둔다 — 안에 넣으면 relative 컨테이너 높이가
    // 커져 박스들의 % 좌표가 이미지와 어긋난다.
    <div className="flex w-fit max-w-full flex-col gap-2">
      {/* w-fit: 하자 박스는 이 div의 %로 위치를 잡으므로 div 크기가 렌더링된 이미지 크기와
          정확히 같아야 정렬이 맞는다. img를 자연 크기로 두고(w-full 강제 금지 — 썸네일 원본
          해상도보다 크게 늘리면 블러 발생, #781/#791) div가 그 크기로 shrink-wrap하게 한다. */}
      <div
        className="relative w-fit max-w-full"
        onMouseDown={drawMode ? onCanvasMouseDown : undefined}
      >
        <img
          src={imgSrc}
          alt="점검 이미지"
          onError={handleImageError}
          onLoad={() => setIsLoaded(true)}
          // 세로 사진이 60vh 높이 상한 때문에 양옆 여백이 과하게 크다는 실사용 피드백(#897) —
          // 79vh로 올려 여백을 줄임. 화면이 낮은 노트북에서는 진행률바·액션 버튼 행이 밀릴 여유가
          // 줄어드는 트레이드오프가 있음(60vh 대비 헤더/버튼용 여유가 40vh→21vh로 축소).
          className={`block max-w-full max-h-[79vh] transition-opacity ${drawMode ? 'cursor-crosshair' : ''} ${isLoaded ? '' : 'opacity-40'}`}
        />
        {!isLoaded && (
          <div
            className="pointer-events-none absolute inset-0 flex items-center justify-center"
            aria-hidden="true"
          >
            <span className="size-8 animate-spin rounded-full border-2 border-neutral-300 border-t-primary" />
          </div>
        )}
        {/* Existing defects — 미확정=마젠타/검수확정=초록(페이즈4 등급색은 원복). 이미지가
            로드되기 전엔 박스를 그리지 않는다(팀 QA 요청, 페이즈5) — 로딩 중인 사진 위에 박스만
            먼저 떠 있으면 엉뚱한 사진에 박스가 걸린 것처럼 보였다. */}
        {isLoaded && sortedDefects.map((defect) => {
          const isSelected = selectedId === defect.id;
          const isReviewed = defect.status !== 'DETECTED';
          const color = colorForDefect(defect);
          // 8자리 hex(#RRGGBBAA)로 알파를 얹는다 — 등급 5개 + 확정 + 미판정마다 별도 rgba 상수를
          // 만드는 대신, 이미 있는 hex 색상에 알파만 붙여 재사용한다(모던 브라우저 전부 지원).
          const softBg = `${color}1A`; // ~10% — 박스 채우기
          const ring = `${color}4D`; // ~30% — 선택 시 바깥 글로우
          return (
            <button
              key={defect.id}
              type="button"
              onClick={() => !drawMode && onSelect?.(defect.id)}
              title={`${defect.type} · ${gradeLabel(defect.grade)} · confidence ${Math.round(defect.confidence * 100)}% · ${isReviewed ? '검수 확정' : '미확정'}`}
              className={`absolute box-border border-2 ${drawMode ? 'cursor-default' : 'cursor-pointer'} rounded-sm transition-all`}
              disabled={drawMode}
              style={{
                left: `${defect.bbox.x * 100}%`,
                top: `${defect.bbox.y * 100}%`,
                width: `${defect.bbox.width * 100}%`,
                height: `${defect.bbox.height * 100}%`,
                borderColor: color,
                backgroundColor: softBg,
                boxShadow: isSelected ? `0 0 0 4px ${ring}` : undefined,
                pointerEvents: drawMode ? 'none' : 'auto',
              }}
            >
              {isSelected && (
                <span
                  className="absolute left-0 top-0 -translate-y-full whitespace-nowrap px-[8px] py-[4px] text-[12px] font-semibold text-white"
                  style={{ backgroundColor: color }}
                >
                  {defect.type} {gradeLabel(defect.grade)}
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
      {/* 박스 색상 범례(팀 QA 요청) — 마젠타가 "미확정", 초록이 "검수 확정"이라는 걸 처음 보는
          사용자도 알 수 있게. */}
      {sortedDefects.length > 0 && (
        <div className="flex items-center gap-3 text-[11px] text-text-muted">
          <span className="flex items-center gap-1">
            <span
              aria-hidden="true"
              className="size-2 rounded-full"
              style={{ backgroundColor: UNCONFIRMED_COLOR }}
            />
            미확정
          </span>
          <span className="flex items-center gap-1">
            <span
              aria-hidden="true"
              className="size-2 rounded-full"
              style={{ backgroundColor: CONFIRMED_COLOR }}
            />
            검수 확정
          </span>
        </div>
      )}
      {unplacedDefects.length > 0 && (
        <div className="flex flex-wrap items-center gap-2">
          <span className="shrink-0 text-xs text-text-muted">위치 미지정:</span>
          {unplacedDefects.map((defect) => {
            const isSelected = selectedId === defect.id;
            // 위치 미지정 칩도 박스와 같은 범례(미확정=마젠타/검수확정=초록)를 따르게 한다(PR머신
            // 리뷰 P2) — 예전엔 상태와 무관하게 항상 마젠타 계열만 써서, 확정된 위치 미지정 하자도
            // 계속 "미확정" 색으로 보여 범례와 어긋났다.
            const color = colorForDefect(defect);
            return (
              <button
                key={defect.id}
                type="button"
                onClick={() => !drawMode && onSelect?.(defect.id)}
                disabled={drawMode}
                title={`${defect.type} · ${gradeLabel(defect.grade)} · 이미지 위 위치가 지정되지 않은 하자`}
                className={`rounded-full border-2 px-3 py-1 text-xs font-medium transition ${
                  isSelected ? 'text-white' : 'text-text-default hover:bg-surface-muted'
                } ${drawMode ? 'cursor-default opacity-50' : 'cursor-pointer'}`}
                style={{
                  borderColor: color,
                  borderStyle: isSelected ? 'solid' : 'dashed',
                  backgroundColor: isSelected ? color : 'transparent',
                }}
              >
                {defect.type} {gradeLabel(defect.grade)}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}
