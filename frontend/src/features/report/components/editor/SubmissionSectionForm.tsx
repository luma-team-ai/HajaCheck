import type { SubmissionSectionData } from '../../types';
import { LabeledTextArea } from './LabeledTextArea';

interface SubmissionSectionFormProps {
  data: SubmissionSectionData;
  onChange: (next: SubmissionSectionData) => void;
  readOnly: boolean;
}

const FIELD_CLASS = 'min-h-12 px-4 py-3';

// 표준서식의 "제출문" — 수신자·계약일·발신 업체 정보. 시스템이 아는 값이 아니라 백엔드가 생산할
// 수 없으므로(계약 당사자는 하자점검 도메인 밖) 수동 입력 섹션으로 편집기에 둔다.
export function SubmissionSectionForm({ data, onChange, readOnly }: SubmissionSectionFormProps) {
  const update = (patch: Partial<SubmissionSectionData>) => onChange({ ...data, ...patch });

  return (
    <section className="flex flex-col gap-6 rounded-lg border border-border bg-surface p-6 sm:p-8">
      <h2 className="text-xl font-medium leading-7 text-heading">제출문</h2>

      <LabeledTextArea
        label="수신자"
        value={data.recipient}
        readOnly={readOnly}
        rows={1}
        placeholder="예: 서울특별시장 귀하"
        textareaClassName={FIELD_CLASS}
        onChange={(value) => update({ recipient: value })}
      />

      <div className="grid grid-cols-1 gap-6 md:grid-cols-2">
        <LabeledTextArea
          label="계약 체결일"
          value={data.contractDate}
          readOnly={readOnly}
          rows={1}
          placeholder="예: 2026년 04월 21일"
          textareaClassName={FIELD_CLASS}
          onChange={(value) => update({ contractDate: value })}
        />
        <LabeledTextArea
          label="대표자"
          value={data.representativeName}
          readOnly={readOnly}
          rows={1}
          textareaClassName={FIELD_CLASS}
          onChange={(value) => update({ representativeName: value })}
        />
      </div>

      <LabeledTextArea
        label="발신 업체명"
        value={data.companyName}
        readOnly={readOnly}
        rows={1}
        textareaClassName={FIELD_CLASS}
        onChange={(value) => update({ companyName: value })}
      />

      <LabeledTextArea
        label="업체 주소"
        value={data.companyAddress}
        readOnly={readOnly}
        rows={2}
        textareaClassName={FIELD_CLASS}
        onChange={(value) => update({ companyAddress: value })}
      />
    </section>
  );
}
