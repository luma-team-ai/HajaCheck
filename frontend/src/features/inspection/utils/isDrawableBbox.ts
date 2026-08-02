import type { Defect } from '../types';

/**
 * 0~1 정규화 좌표로 실제로 그릴 수 있는 bbox인지 — 백엔드가 null을 줄 수 있고(수동 추가 하자에서
 * "박스 없이 계속", AI가 bbox 없이 내려준 경우), 범위를 벗어난 값은 레이아웃을 깨뜨린다.
 *
 * <p>원래 report/DefectPhoto에만 있던 가드다. 뷰어(DefectOverlay)에는 같은 가드가 없어 bbox가
 * null인 하자가 `null * 100 = 0` 으로 **0×0 픽셀 버튼**이 되어 클릭 자체가 불가능했고(#1395),
 * 그 하자는 totalCount 분모에는 남아 "점검 요약"이 영구히 잠겼다. 두 화면이 같은 판정을 쓰도록
 * Defect 타입 옆(inspection/utils)으로 올린다 — bbox를 그리는 화면이 늘어도 판정은 여기 하나다.
 *
 * <p>bbox가 없을 때의 **처리**는 화면마다 다르다: 보고서는 박스를 생략하고, 뷰어는 선택 가능한
 * "위치 미지정" 칩으로 대체한다(선택 수단이 박스 클릭뿐이라 생략하면 검수가 막힌다).
 */
export function isDrawableBbox(defect: Defect): boolean {
  const bbox = defect.bbox;
  if (!bbox) return false;
  const values = [bbox.x, bbox.y, bbox.width, bbox.height];
  if (values.some((value) => typeof value !== 'number' || !Number.isFinite(value))) return false;
  return bbox.width > 0 && bbox.height > 0;
}
