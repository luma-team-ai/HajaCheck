import type { ReportContent } from '../../types';
import { LabeledTextArea } from './LabeledTextArea';

interface SummarySectionProps {
  content: ReportContent;
  onChange: (next: ReportContent) => void;
  readOnly: boolean;
}

export function SummarySection({ content, onChange, readOnly }: SummarySectionProps) {
  const updateSummary = (patch: Partial<ReportContent['summary']>) =>
    onChange({ ...content, summary: { ...content.summary, ...patch } });

  return (
    <section className="flex flex-col gap-6">
      <label className="flex flex-col gap-2">
        <span className="text-xs font-medium tracking-wide text-text-default">책임기술자</span>
        <input
          className="w-full rounded-lg border border-border bg-surface px-4 py-3 text-sm leading-6 text-text-default outline-none transition placeholder:text-text-muted focus:border-primary focus:ring-2 focus:ring-primary/10 read-only:cursor-default read-only:bg-surface-muted read-only:text-text-muted"
          value={content.summary.responsible_engineer_name ?? ''}
          readOnly={readOnly}
          placeholder="책임기술자명을 입력하세요"
          onChange={(event) => updateSummary({ responsible_engineer_name: event.target.value })}
        />
      </label>
      <LabeledTextArea
        label="종합 의견"
        value={content.summary.overall_opinion}
        readOnly={readOnly}
        rows={3}
        textareaClassName="min-h-20"
        onChange={(value) => updateSummary({ overall_opinion: value })}
      />
    </section>
  );
}
