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
    <section className="flex flex-col gap-6">
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
