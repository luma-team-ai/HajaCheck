import type {
  GenericManualSectionData,
  ManualSection,
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

function hasManualSectionContent(section: ManualSection): boolean {
  if (section.type === 'submission') {
    const data = section.data as SubmissionSectionData;
    return REQUIRED_SUBMISSION_FIELDS.every((field) => hasText(data[field] ?? ''));
  }

  if (section.type === 'participants') {
    return (section.data as ParticipantsSectionData).entries.some((entry) =>
      Object.values(entry).some(hasText),
    );
  }

  return hasText((section.data as GenericManualSectionData).body);
}

export function getEmptyManualSectionLabels(content: ReportContent | null): string[] {
  return (content?.manualSections ?? [])
    .filter((section) => !hasManualSectionContent(section))
    .map((section) => section.title);
}
