import type { GenericManualSectionData } from '../../types';

interface GenericManualSectionFormProps {
  title: string;
  data: GenericManualSectionData;
  onChange: (next: GenericManualSectionData) => void;
  readOnly: boolean;
}

// DB/AI 스키마에 없는 표준서식 항목은 content_json의 수동 섹션으로 저장한다.
// PDF에서는 같은 제목의 관공서 표 한 칸으로 렌더링된다.
export function GenericManualSectionForm({
  title,
  data,
  onChange,
  readOnly,
}: GenericManualSectionFormProps) {
  return (
    <section className="flex flex-col gap-3">
      <div>
        <h2 className="text-xl font-medium leading-7 text-heading">{title}</h2>
        <p className="text-sm text-text-muted">
          기존 DB에 없는 항목입니다. 필요한 문구·표 내용을 직접 입력하면 PDF에 같은 섹션으로 반영됩니다.
        </p>
      </div>
      <label className="flex flex-col gap-1 text-sm text-text-muted">
        내용
        <textarea
          value={data.body}
          onChange={(event) => onChange({ body: event.target.value })}
          readOnly={readOnly}
          rows={7}
          className="rounded-lg border border-border bg-surface px-3 py-2 text-sm text-text-default outline-none transition focus:border-primary disabled:opacity-60"
          placeholder="해당 섹션에 들어갈 내용을 입력하세요."
        />
      </label>
    </section>
  );
}
