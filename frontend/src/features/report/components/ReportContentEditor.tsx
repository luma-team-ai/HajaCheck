import type { ReportContent } from '../types';
import { OverviewSection } from './editor/OverviewSection';
import { SummarySection } from './editor/SummarySection';
import { DetailSection } from './editor/DetailSection';
import { RecommendationSection } from './editor/RecommendationSection';

interface ReportContentEditorProps {
  content: ReportContent;
  onChange: (next: ReportContent) => void;
  readOnly: boolean;
  defectImageUrls?: Array<string | null | undefined>;
}

// 데이터 상태는 상위 페이지가 소유하고, 이 컴포넌트는 Figma 본문 섹션의 배치만 담당한다.
export function ReportContentEditor({
  content,
  onChange,
  readOnly,
  defectImageUrls = [],
}: ReportContentEditorProps) {
  return (
    <div className="flex flex-col gap-6">
      <OverviewSection content={content} onChange={onChange} readOnly={readOnly} />
      <SummarySection content={content} onChange={onChange} readOnly={readOnly} />
      <DetailSection
        content={content}
        onChange={onChange}
        readOnly={readOnly}
        imageUrls={defectImageUrls}
      />
      <RecommendationSection content={content} onChange={onChange} readOnly={readOnly} />
    </div>
  );
}
