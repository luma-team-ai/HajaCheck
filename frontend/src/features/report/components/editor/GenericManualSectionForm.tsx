import type { GenericManualSectionData } from '../../types';
import { LabeledTextArea } from './LabeledTextArea';

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
      <p className="text-sm text-text-muted">
        자동으로 작성되지 않는 서식 항목입니다. 현장 판단이나 별도 산정 결과가 있으면 입력해 주세요.
        입력한 내용은 같은 제목의 PDF 섹션에 반영됩니다.
      </p>
      <LabeledTextArea
        label={`${title} 내용`}
        value={data.body}
        onChange={(body) => onChange({ body })}
        readOnly={readOnly}
        rows={7}
        placeholder="해당 섹션에 들어갈 내용을 입력하세요."
      />
    </section>
  );
}
