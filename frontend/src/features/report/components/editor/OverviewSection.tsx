import type { ReportContent } from '../../types';
import { LabeledTextArea } from './LabeledTextArea';

interface OverviewSectionProps {
  content: ReportContent;
  onChange: (next: ReportContent) => void;
  readOnly: boolean;
}

const FIELD_CLASS = 'min-h-20 px-5 py-4';

export function OverviewSection({ content, onChange, readOnly }: OverviewSectionProps) {
  const updateOverview = (patch: Partial<ReportContent['overview']>) =>
    onChange({ ...content, overview: { ...content.overview, ...patch } });

  return (
    <section className="flex flex-col gap-6 rounded-lg border border-border bg-surface p-6 sm:p-8">
      <div className="flex items-center justify-between">
        <h2 className="text-xl font-medium leading-7 text-heading">개요</h2>
        <svg className="h-4 w-4 text-heading" viewBox="0 0 16 16" fill="none" aria-hidden="true">
          <path d="m4 10 4-4 4 4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      </div>

      <LabeledTextArea
        label="점검 목적"
        value={content.overview.purpose}
        readOnly={readOnly}
        rows={3}
        textareaClassName={`min-h-24 ${FIELD_CLASS}`}
        onChange={(value) => updateOverview({ purpose: value })}
      />

      <div className="grid grid-cols-1 gap-6 md:grid-cols-2">
        <LabeledTextArea
          label="시설물 개요"
          value={content.overview.facility_summary}
          readOnly={readOnly}
          rows={3}
          textareaClassName={`min-h-28 ${FIELD_CLASS}`}
          onChange={(value) => updateOverview({ facility_summary: value })}
        />
        <LabeledTextArea
          label="점검 범위"
          value={content.overview.scope}
          readOnly={readOnly}
          rows={3}
          textareaClassName={`min-h-28 ${FIELD_CLASS}`}
          onChange={(value) => updateOverview({ scope: value })}
        />
      </div>
    </section>
  );
}
