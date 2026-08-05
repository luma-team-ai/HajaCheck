import type {
  GenericManualSectionData,
  LocationDrawingPhotosSectionData,
  ManualSection,
  ParticipantEntry,
  ParticipantsSectionData,
  ReportContent,
  SubmissionSectionData,
} from '../types';

function hasText(value: string): boolean {
  return value.trim().length > 0;
}

const REQUIRED_SUBMISSION_FIELDS: Array<keyof SubmissionSectionData> = [
  'recipient',
  'contractDate',
  'companyName',
  'companyAddress',
  'representativeName',
];

const REQUIRED_PARTICIPANT_FIELDS: Array<keyof ParticipantEntry> = [
  'role',
  'name',
  'qualification',
  'period',
];

function hasManualSectionContent(section: ManualSection): boolean {
  if (section.type === 'submission') {
    const data = section.data as SubmissionSectionData;
    return REQUIRED_SUBMISSION_FIELDS.every((field) => hasText(data[field] ?? ''));
  }

  if (section.type === 'participants') {
    const entries = (section.data as ParticipantsSectionData).entries ?? [];
    const nonInitialEntries = entries.filter((entry) =>
      REQUIRED_PARTICIPANT_FIELDS.some((field) => hasText(entry[field] ?? '')),
    );
    if (nonInitialEntries.length === 0) {
      return false;
    }
    return nonInitialEntries.every((entry) =>
      REQUIRED_PARTICIPANT_FIELDS.every((field) => hasText(entry[field] ?? '')),
    );
  }

  if (section.type === 'location-drawing-photos') {
    return (section.data as LocationDrawingPhotosSectionData).images.length > 0;
  }

  return hasText((section.data as GenericManualSectionData).body);
}

export function getEmptyManualSectionLabels(content: ReportContent | null): string[] {
  return (content?.manualSections ?? [])
    .filter((section) => !hasManualSectionContent(section))
    .map((section) => section.title);
}

const ALL_REPORT_SECTIONS = [
  'overview',
  'summary',
  'details',
  'recommendation',
] as const;

function getEnabledSections(content: ReportContent | null): Set<string> {
  return new Set(
    content?.reportOptions?.sections ?? ALL_REPORT_SECTIONS,
  );
}

export function getMissingFinalReportRequiredLabels(content: ReportContent | null): string[] {
  const labels: string[] = [];
  const enabled = getEnabledSections(content);

  if (enabled.has('overview')) {
    if (!hasText(content?.overview?.purpose ?? '')) {
      labels.push('기본현황 > 점검 목적');
    }
    if (!hasText(content?.overview?.facility_summary ?? '')) {
      labels.push('기본현황 > 시설물 개요');
    }
    if (!hasText(content?.overview?.scope ?? '')) {
      labels.push('기본현황 > 점검 범위');
    }
  }

  if (enabled.has('summary')) {
    if (!hasText(content?.summary?.overall_opinion ?? '')) {
      labels.push('결과 요약 > 종합 의견');
    }
  }

  if (enabled.has('details')) {
    if ((content?.detail?.items ?? []).length === 0) {
      labels.push('진단 외관조사결과 기본사항');
    }

    content?.detail?.items.forEach((item, index) => {
      const prefix = `진단 외관조사결과 기본사항 > 하자 #${index + 1}`;

      if (!hasText(item.description ?? '')) {
        labels.push(`${prefix} 설명`);
      }
      if (!hasText(item.cause ?? '')) {
        labels.push(`${prefix} 원인 분석`);
      }
    });
  }

  if (enabled.has('recommendation')) {
    if ((content?.recommendation?.items ?? []).length === 0) {
      labels.push('보수ㆍ보강(안)');
    }

    content?.recommendation?.items.forEach((item, index) => {
      if (!hasText(item.method ?? '')) {
        labels.push(`보수ㆍ보강(안) > 권고 #${index + 1} 방법`);
      }
    });
  }

  return labels;
}
