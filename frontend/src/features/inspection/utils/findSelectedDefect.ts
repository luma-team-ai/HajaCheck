import type { Defect } from '../types';

/**
 * 명시적 선택이 없거나 무효할 때의 폴백 대상 — 미확정(DETECTED) 하자를 우선한다(팀 QA 발견,
 * 페이즈7). 이전엔 항상 currentDefects[0]이라, 다음/이전 이미지로 이동할 때마다 selectedDefectId가
 * undefined로 리셋되면서 그 이미지에 확정된 하자가 먼저 있으면 이미 끝난 박스가 활성화됐다 —
 * 검수자가 정작 봐야 할 미확정 박스를 매번 다시 찾아 클릭해야 했다. 전부 확정됐으면(더 고를
 * 미확정이 없음) 그대로 첫 항목으로 둔다.
 */
function firstPendingOrFallback(currentDefects: Defect[]): Defect | undefined {
  return currentDefects.find((d) => d.status === 'DETECTED') ?? currentDefects[0];
}

/**
 * 선택된 하자를 찾는다. 수동 추가 하자(mediaId=null)는 mediaGroups/currentDefects에서
 * 제외되므로 전체 목록(allDefects)에서 먼저 찾고, 없으면 현재 이미지 소속(currentDefects)에서
 * 찾는다. 둘 다 없으면 미확정 우선 폴백(firstPendingOrFallback)으로 넘어간다(#787, #975).
 */
export function findSelectedDefect(
  allDefects: Defect[],
  currentDefects: Defect[],
  selectedDefectId: number | undefined,
): Defect | undefined {
  if (selectedDefectId == null) return firstPendingOrFallback(currentDefects);
  return (
    allDefects.find((d) => d.id === selectedDefectId && d.mediaId == null) ??
    currentDefects.find((d) => d.id === selectedDefectId) ??
    firstPendingOrFallback(currentDefects)
  );
}
