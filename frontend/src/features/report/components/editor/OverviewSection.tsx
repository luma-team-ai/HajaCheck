import type { ReportContent } from '../../types';
import { LabeledTextArea } from './LabeledTextArea';

interface OverviewSectionProps {
  content: ReportContent;
  onChange: (next: ReportContent) => void;
  readOnly: boolean;
}

export function OverviewSection({ content, onChange, readOnly }: OverviewSectionProps) {
  const updateOverview = (patch: Partial<ReportContent['overview']>) =>
    onChange({ ...content, overview: { ...content.overview, ...patch } });

  return (
    <section className="flex flex-col gap-4 rounded-2xl border border-zinc-200 bg-white p-8">
      <h2 className="text-lg font-semibold text-text-default">개요</h2>
      <LabeledTextArea
        label="점검 목적"
        value={content.overview.purpose}
        readOnly={readOnly}
        onChange={(v) => updateOverview({ purpose: v })}
      />
      <LabeledTextArea
        label="시설물 개요"
        value={content.overview.facility_summary}
        readOnly={readOnly}
        onChange={(v) => updateOverview({ facility_summary: v })}
      />
      <LabeledTextArea
        label="점검 범위"
        value={content.overview.scope}
        readOnly={readOnly}
        onChange={(v) => updateOverview({ scope: v })}
      />
    </section>
  );
}
