import type {
  DefectGrade,
  DefectImageGroup,
  DefectStatus,
  DefectType,
  InspectionDefect,
} from '../types';

export type DefectImageGroupFilters = {
  type: DefectType | '';
  grade: DefectGrade | '';
  status: Exclude<DefectStatus, 'DETECTED'> | '';
};

export type DefectImageGroupSort =
  | 'createdAt-desc'
  | 'createdAt-asc'
  | 'confidence-desc'
  | 'confidence-asc';

const GRADE_PRIORITY: Record<DefectGrade, number> = { A: 1, B: 2, C: 3, D: 4, E: 5 };

function compareRepresentative(a: InspectionDefect, b: InspectionDefect): number {
  const gradeDifference = (b.grade ? GRADE_PRIORITY[b.grade] : 0) - (a.grade ? GRADE_PRIORITY[a.grade] : 0);
  if (gradeDifference !== 0) return gradeDifference;
  if (a.confidence !== b.confidence) return b.confidence - a.confidence;
  const createdAtDifference = b.createdAt.localeCompare(a.createdAt);
  if (createdAtDifference !== 0) return createdAtDifference;
  return b.id - a.id;
}

function buildGroup(key: string, mediaId: number | null, defects: InspectionDefect[]): DefectImageGroup {
  const ordered = [...defects].sort(compareRepresentative);
  const representative = ordered[0];
  return {
    key,
    mediaId,
    imageUrl: representative.imageUrl,
    defects: ordered,
    representative,
    latestCreatedAt: defects.reduce(
      (latest, defect) => (defect.createdAt > latest ? defect.createdAt : latest),
      defects[0].createdAt,
    ),
    highestConfidence: Math.max(...defects.map((defect) => defect.confidence)),
  };
}

export function groupDefectsByImage(defects: InspectionDefect[]): DefectImageGroup[] {
  const managedDefects = defects.filter((defect) => defect.status !== 'DETECTED');
  const groups = new Map<string, InspectionDefect[]>();

  managedDefects.forEach((defect) => {
    const key = defect.mediaId == null ? `defect:${defect.id}` : `media:${defect.mediaId}`;
    const current = groups.get(key);
    if (current) current.push(defect);
    else groups.set(key, [defect]);
  });

  return Array.from(groups.entries()).map(([key, groupDefects]) =>
    buildGroup(key, groupDefects[0].mediaId ?? null, groupDefects),
  );
}

export function groupMatchesFilters(
  group: DefectImageGroup,
  filters: DefectImageGroupFilters,
): boolean {
  return group.defects.some(
    (defect) =>
      (filters.type === '' || defect.type === filters.type) &&
      (filters.grade === '' || defect.grade === filters.grade) &&
      (filters.status === '' || defect.status === filters.status),
  );
}

export function sortDefectImageGroups(
  groups: DefectImageGroup[],
  sort: DefectImageGroupSort,
): DefectImageGroup[] {
  return [...groups].sort((a, b) => {
    if (sort === 'confidence-desc') return b.highestConfidence - a.highestConfidence;
    if (sort === 'confidence-asc') return a.highestConfidence - b.highestConfidence;
    if (sort === 'createdAt-asc') return a.latestCreatedAt.localeCompare(b.latestCreatedAt);
    return b.latestCreatedAt.localeCompare(a.latestCreatedAt);
  });
}

export function findDefectImageGroup(
  groups: DefectImageGroup[],
  defectId: number,
): DefectImageGroup | null {
  return groups.find((group) => group.defects.some((defect) => defect.id === defectId)) ?? null;
}
