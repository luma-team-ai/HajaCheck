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
  'location-drawing-photos': '현황도 및 전경사진',
} as const;

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
