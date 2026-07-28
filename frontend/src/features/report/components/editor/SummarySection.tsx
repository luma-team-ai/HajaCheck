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
    <section className="flex flex-col gap-6 rounded-2xl border border-zinc-200 bg-white p-6 sm:p-8">
      <h2 className="text-xl font-medium leading-7 text-zinc-900">요약 결론</h2>

      <div className="rounded-[32px] border border-zinc-200 bg-zinc-50/70 px-5 py-4">
        <LabeledTextArea
          label="종합 의견"
          hideLabel
          value={content.summary.overall_opinion}
          readOnly={readOnly}
          rows={3}
          textareaClassName="min-h-20 resize-y border-0 bg-transparent p-0 shadow-none focus:border-transparent focus:ring-0 disabled:bg-transparent"
          onChange={(value) => updateSummary({ overall_opinion: value })}
        />
        <div className="mt-3 inline-flex items-center gap-2 text-xs font-medium text-indigo-600">
          <svg className="h-4 w-4" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="m8 1 .8 2.2L11 4l-2.2.8L8 7l-.8-2.2L5 4l2.2-.8L8 1ZM3.5 7l.6 1.4L5.5 9l-1.4.6L3.5 11l-.6-1.4L1.5 9l1.4-.6L3.5 7Zm8.5 1 .9 2.1L15 11l-2.1.9L12 14l-.9-2.1L9 11l2.1-.9L12 8Z" fill="currentColor" />
          </svg>
          AI 요약 생성됨
        </div>
      </div>
    </section>
  );
}
