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

function hasManualSectionContent(section: ManualSection): boolean {
  if (section.type === 'submission') {
    return Object.values(section.data as SubmissionSectionData).some(hasText);
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
