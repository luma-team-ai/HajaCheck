import { FIXED_SECTION_KEYS, type ManualSection, type ReportContent, type SectionKey } from '../types';

const DEFAULT_ORDER: SectionKey[] = [...FIXED_SECTION_KEYS];

const FIXED_SECTION_LABELS: Record<(typeof FIXED_SECTION_KEYS)[number], string> = {
  overview: '기본현황',
  summary: '결과 요약',
  detail: '진단 외관조사결과 기본사항',
  recommendation: '보수ㆍ보강(안)',
  photos: '부위별 사진',
};

export const MANUAL_SECTION_LABELS = {
  submission: '제출문',
  'overview-form': '기본현황',
  'inspection-result-repair': '상태평가 결과 및 보수ㆍ보강',
  participants: '참여 기술진 명단',
  'summary-opinion': '책임기술자 종합의견',
  'member-condition-repair': '부위별 상태평가 결과 및 보수ㆍ보강',
  'safety-assessment': '안전성평가 결과',
  'field-test': '현장시험(비파괴 및 추가시험)',
  'facility-status': '시설물 현황',
  'location-drawing-photos': '위치도ㆍ전경 사진ㆍ종ㆍ평면도ㆍ현황도',
} as const;

// 표준 서식(정밀안전진단 보고서) 기준 섹션 순서 — 제출문→기본현황→종합의견/결과요약→
// 진단 외관조사결과/보수·보강→안전성평가→현장시험→시설물현황→참여기술진→위치도·사진 순.
// "+ 서식 섹션 추가"로 새 섹션을 넣을 때 항상 맨 끝에 붙던 걸(#1379), 이 순서에 맞는 위치로
// 자동 삽입하는 데 쓴다. 목록에 없는 타입(향후 신규 추가 시)은 안전하게 맨 끝으로 폴백한다.
const SECTION_CANONICAL_ORDER: SectionKey[] = [
  'submission',
  'overview',
  'overview-form',
  'summary-opinion',
  'summary',
  'detail',
  'inspection-result-repair',
  'member-condition-repair',
  'recommendation',
  'safety-assessment',
  'field-test',
  'facility-status',
  'participants',
  'location-drawing-photos',
  'photos',
];

function sectionTypeOf(key: SectionKey, manualSections: ManualSection[]): SectionKey {
  if ((FIXED_SECTION_KEYS as readonly string[]).includes(key)) return key;
  return manualSections.find((section) => section.id === key)?.type ?? key;
}

/** 새 섹션(id=newKey, 타입=newType)을 표준 순서상 맞는 위치에 삽입한 새 순서 배열을 반환한다. */
export function insertSectionAtCanonicalPosition(
  order: SectionKey[],
  newKey: SectionKey,
  newType: SectionKey,
  manualSections: ManualSection[],
): SectionKey[] {
  const newRank = SECTION_CANONICAL_ORDER.indexOf(newType);
  if (newRank === -1) return [...order, newKey];
  const insertIndex = order.findIndex((key) => {
    const rank = SECTION_CANONICAL_ORDER.indexOf(sectionTypeOf(key, manualSections));
    return rank !== -1 && rank > newRank;
  });
  if (insertIndex === -1) return [...order, newKey];
  const next = [...order];
  next.splice(insertIndex, 0, newKey);
  return next;
}

/**
 * 저장된 sectionOrder를 신뢰하되, 다음 두 불일치를 정리해 편집기·PDF가 항상 같은 순서를 본다.
 *   1. 삭제된 수동 섹션의 잔여 id → 제거.
 *   2. 아직 순서에 없는 항목(신규 추가 직후, 또는 sectionOrder 없는 구버전 저장분) → 끝에 추가.
 * 고정 4종은 항상 존재해야 하므로 누락 시 안전하게 보충한다(레거시 content 방어).
 */
export function resolveSectionOrder(content: ReportContent): SectionKey[] {
  const manualIds = (content.manualSections ?? []).map((section) => section.id);
  const known = new Set<SectionKey>([...FIXED_SECTION_KEYS, ...manualIds]);
  const stored = content.sectionOrder && content.sectionOrder.length > 0 ? content.sectionOrder : DEFAULT_ORDER;

  const resolved = stored.filter((key) => known.has(key));
  for (const id of manualIds) {
    if (!resolved.includes(id)) resolved.push(id);
  }
  for (const key of FIXED_SECTION_KEYS) {
    if (!resolved.includes(key)) resolved.push(key);
  }
  return resolved;
}

export function sectionLabel(key: SectionKey, manualSections: ManualSection[] | undefined): string {
  if ((FIXED_SECTION_KEYS as readonly string[]).includes(key)) {
    return FIXED_SECTION_LABELS[key as (typeof FIXED_SECTION_KEYS)[number]];
  }
  return manualSections?.find((section) => section.id === key)?.title ?? '섹션';
}

export function isFixedSectionKey(key: SectionKey): key is (typeof FIXED_SECTION_KEYS)[number] {
  return (FIXED_SECTION_KEYS as readonly string[]).includes(key);
}

/** 배열의 index 위치 항목을 targetIndex로 옮긴다(드래그 재정렬 공통 로직, 제자리 이동은 no-op). */
export function moveItem<T>(items: T[], fromIndex: number, toIndex: number): T[] {
  if (fromIndex === toIndex || fromIndex < 0 || toIndex < 0 || fromIndex >= items.length) return items;
  const next = [...items];
  const [moved] = next.splice(fromIndex, 1);
  next.splice(toIndex, 0, moved);
  return next;
}

export function createManualSectionId(type: ManualSection['type']): string {
  return `manual-${type}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}
