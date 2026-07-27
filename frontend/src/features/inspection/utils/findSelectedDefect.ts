import type { Defect } from '../types';

/**
 * 선택된 하자를 찾는다. 수동 추가 하자(mediaId=null)는 mediaGroups/currentDefects에서
 * 제외되므로 전체 목록(allDefects)에서 먼저 찾고, 없으면 현재 이미지 소속(currentDefects)에서
 * 찾는다. 둘 다 없으면 currentDefects의 첫 항목으로 폴백한다(#787, #975).
 */
export function findSelectedDefect(
  allDefects: Defect[],
  currentDefects: Defect[],
  selectedDefectId: number | undefined,
): Defect | undefined {
  if (selectedDefectId == null) return currentDefects[0];
  return (
    allDefects.find((d) => d.id === selectedDefectId && d.mediaId == null) ??
    currentDefects.find((d) => d.id === selectedDefectId) ??
    currentDefects[0]
  );
}
