import type { ReportContent } from '../types';
import { OverviewSection } from './editor/OverviewSection';
import { SummarySection } from './editor/SummarySection';
import { DetailSection } from './editor/DetailSection';
import { RecommendationSection } from './editor/RecommendationSection';

interface ReportContentEditorProps {
  content: ReportContent;
  onChange: (next: ReportContent) => void;
  readOnly: boolean;
}

// Figma 시안(핸드오프 #1088 후속)에 따라 4섹션으로 분해 — 개요/요약 결론/상세 내역/조치 권고.
// 각 섹션은 자체 컴포넌트로 캡슐화(React 코드 컨벤션 §5 — 200라인 초과 시 분리).
// 데이터 계층(상태/핸들러)은 부모가 소유하며 이 컴포넌트는 onChange로 단방향 전달만 담당.
export function ReportContentEditor({ content, onChange, readOnly }: ReportContentEditorProps) {
  return (
    <div className="flex flex-col gap-6">
      <OverviewSection content={content} onChange={onChange} readOnly={readOnly} />
      <SummarySection content={content} onChange={onChange} readOnly={readOnly} />
      <DetailSection content={content} onChange={onChange} readOnly={readOnly} />
      <RecommendationSection content={content} onChange={onChange} readOnly={readOnly} />
    </div>
  );
}
