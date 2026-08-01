import { useAuthStore } from '../../auth/store/authStore';
import type {
  GenericManualSectionData,
  ManualSection,
  ManualSectionType,
  ParticipantsSectionData,
  ReportContent,
  SubmissionSectionData,
} from '../types';
import {
  createManualSectionId,
  insertSectionAtCanonicalPosition,
  isFixedSectionKey,
  MANUAL_SECTION_LABELS,
  moveItem,
  resolveSectionOrder,
  sectionLabel,
} from '../utils/sectionOrder';
import { AddSectionMenu } from './editor/AddSectionMenu';
import type { DefectPhotoGroup } from './editor/DefectPhoto';
import { DetailSection } from './editor/DetailSection';
import { DraggableSectionSlot } from './editor/DraggableSectionSlot';
import { GenericManualSectionForm } from './editor/GenericManualSectionForm';
import { OverviewSection } from './editor/OverviewSection';
import { ParticipantsSectionForm } from './editor/ParticipantsSectionForm';
import { PhotosSectionPreview } from './editor/PhotosSectionPreview';
import { RecommendationSection } from './editor/RecommendationSection';
import { SubmissionSectionForm } from './editor/SubmissionSectionForm';
import { SummarySection } from './editor/SummarySection';

interface ReportContentEditorProps {
  content: ReportContent;
  onChange: (next: ReportContent) => void;
  readOnly: boolean;
  /** 하자 상세 항목 순서와 1:1로 맞춘 사진(항목별 강조) — DetailSection이 쓴다(#1333). */
  defectPhotos?: DefectPhotoGroup[];
  /** 사진 단위로 묶인 하자 그룹(사진 1장에 박스 여러 개) — PhotosSectionPreview가 쓴다(#1333). */
  defectPhotoGroups?: DefectPhotoGroup[];
}

const EMPTY_SUBMISSION: SubmissionSectionData = {
  recipient: '',
  contractDate: '',
  companyName: '',
  companyAddress: '',
  representativeName: '',
};

const EMPTY_PARTICIPANTS: ParticipantsSectionData = { entries: [] };

// 제출문의 발신 업체명은 매번 수동 입력하지 않아도 되는 값이라 로그인 세션(companyName)에서
// 기본값을 채운다(#1379). 계약 체결일은 "오늘"로 자동 채우면 빈 섹션 검증(#1323 P3, 아무 필드도
// 안 채운 섹션은 저장을 막는 기능)이 항상 무력화되므로 일부러 자동 채우지 않는다 — 수신자·업체
// 주소·대표자명과 마찬가지로 앱에 안정적인 조회 소스도 없어 수동 입력으로 남긴다.
function defaultManualData(type: ManualSectionType, companyName?: string | null): ManualSection['data'] {
  if (type === 'submission') {
    return { ...EMPTY_SUBMISSION, companyName: companyName ?? '' };
  }
  if (type === 'participants') return { ...EMPTY_PARTICIPANTS };
  return { body: '' };
}

// 데이터 상태는 상위 페이지가 소유하고, 이 컴포넌트는 본문 섹션의 배치·순서만 담당한다.
// 순서는 content.sectionOrder에 저장되고 PDF 내보내기(exportReportToPdf)가 같은 순서를 읽는다
// (resolveSectionOrder가 두 곳의 단일 SOT) — 여기서 순서를 바꾸면 PDF 출력 순서도 바뀐다.
export function ReportContentEditor({
  content,
  onChange,
  readOnly,
  defectPhotos = [],
  defectPhotoGroups = [],
}: ReportContentEditorProps) {
  const order = resolveSectionOrder(content);
  const manualSections = content.manualSections ?? [];
  const companyName = useAuthStore((state) => state.user?.companyName);

  const reorder = (fromIndex: number, toIndex: number) => {
    onChange({ ...content, sectionOrder: moveItem(order, fromIndex, toIndex) });
  };

  const addManualSection = (type: ManualSectionType) => {
    const section: ManualSection = {
      id: createManualSectionId(type),
      type,
      title: MANUAL_SECTION_LABELS[type],
      data: defaultManualData(type, companyName),
    };
    onChange({
      ...content,
      manualSections: [...manualSections, section],
      sectionOrder: insertSectionAtCanonicalPosition(order, section.id, type, manualSections),
    });
  };

  const removeManualSection = (id: string) => {
    onChange({
      ...content,
      manualSections: manualSections.filter((section) => section.id !== id),
      sectionOrder: order.filter((key) => key !== id),
    });
  };

  const updateManualSectionData = (id: string, data: ManualSection['data']) => {
    onChange({
      ...content,
      manualSections: manualSections.map((section) => (section.id === id ? { ...section, data } : section)),
    });
  };

  return (
    <div className="flex flex-col gap-6">
      {order.map((key, index) => {
        const label = sectionLabel(key, manualSections);
        const slotProps = { index, label, readOnly, onReorder: reorder };

        if (isFixedSectionKey(key)) {
          const body =
            key === 'overview' ? (
              <OverviewSection content={content} onChange={onChange} readOnly={readOnly} />
            ) : key === 'summary' ? (
              <SummarySection content={content} onChange={onChange} readOnly={readOnly} />
            ) : key === 'detail' ? (
              <DetailSection
                content={content}
                onChange={onChange}
                readOnly={readOnly}
                defectPhotos={defectPhotos}
              />
            ) : key === 'recommendation' ? (
              <RecommendationSection content={content} onChange={onChange} readOnly={readOnly} />
            ) : (
              <PhotosSectionPreview photoGroups={defectPhotoGroups} />
            );
          return (
            <DraggableSectionSlot key={key} {...slotProps} removable={false}>
              {body}
            </DraggableSectionSlot>
          );
        }

        const manual = manualSections.find((section) => section.id === key);
        if (!manual) return null;
        return (
          <DraggableSectionSlot
            key={key}
            {...slotProps}
            removable
            onRemove={() => removeManualSection(manual.id)}
          >
            {manual.type === 'submission' ? (
              <SubmissionSectionForm
                data={manual.data as SubmissionSectionData}
                readOnly={readOnly}
                onChange={(data) => updateManualSectionData(manual.id, data)}
              />
            ) : manual.type === 'participants' ? (
              <ParticipantsSectionForm
                data={manual.data as ParticipantsSectionData}
                readOnly={readOnly}
                onChange={(data) => updateManualSectionData(manual.id, data)}
              />
            ) : (
              <GenericManualSectionForm
                title={manual.title}
                data={manual.data as GenericManualSectionData}
                readOnly={readOnly}
                onChange={(data) => updateManualSectionData(manual.id, data)}
              />
            )}
          </DraggableSectionSlot>
        );
      })}

      {!readOnly && (
        <AddSectionMenu
          existingTypes={manualSections.map((section) => section.type)}
          onAdd={addManualSection}
        />
      )}
    </div>
  );
}
