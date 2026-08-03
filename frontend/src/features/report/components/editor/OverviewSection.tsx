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
    <section className="flex flex-col gap-6">
      <LabeledTextArea
        label="점검 목적"
        value={content.overview.purpose}
        readOnly={readOnly}
        rows={3}
        textareaClassName="min-h-24"
        onChange={(value) => updateOverview({ purpose: value })}
      />

      <div className="grid grid-cols-1 gap-6 md:grid-cols-2">
        <LabeledTextArea
          label="시설물 개요"
          value={content.overview.facility_summary}
          readOnly={readOnly}
          rows={3}
          textareaClassName="min-h-28"
          onChange={(value) => updateOverview({ facility_summary: value })}
        />
        <LabeledTextArea
          label="점검 범위"
          value={content.overview.scope}
          readOnly={readOnly}
          rows={3}
          textareaClassName="min-h-28"
          onChange={(value) => updateOverview({ scope: value })}
        />
      </div>

      <LabeledTextArea
        label="공중이 이용하는 부위의 결함"
        value={content.overview.public_use_area_defect ?? ''}
        readOnly={readOnly}
        rows={2}
        textareaClassName="min-h-16"
        placeholder="보도·난간 등 공중이 이용하는 부위에 결함이 있으면 입력하세요. 없으면 비워두세요."
        onChange={(value) => updateOverview({ public_use_area_defect: value })}
      />
    </section>
  );
}
