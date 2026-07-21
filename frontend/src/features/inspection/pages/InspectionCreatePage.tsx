import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { FacilityOverviewPanel } from '../../../shared/components/FacilityOverviewPanel';
import { DueDateBadge } from '../components/DueDateBadge';
import { InspectionCreateModal } from '../components/InspectionCreateModal';
import { useFacilityDetail } from '../hooks/useFacilityDetail';
import { useFacilityOverview } from '../hooks/useFacilityOverview';

// 라우트에 시설물 컨텍스트가 없어 쿼리파라미터(?facilityId=)로 대상 지정,
// 미지정 시 데모 대표 시설물(강남 오피스타워 A동, id=1)을 기본값으로 사용
// (facility feature의 FacilityDetailPage와 동일 패턴).
const DEFAULT_FACILITY_ID = 1;

// 점검(회차) 생성 — 화면 자체는 시설물 개요(Figma "hajaCheck Facility Detail - Fixed Images",
// node-id 1-1401)와 동일 레이아웃을 쓴다(shared/FacilityOverviewPanel). 실제 점검 생성(AP-004,
// POST /api/inspections)은 "+ 새 점검" 버튼을 눌러 여는 모달(InspectionCreateModal)이 담당한다.
export function InspectionCreatePage() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const facilityId = Number(searchParams.get('facilityId')) || DEFAULT_FACILITY_ID;
  const [isModalOpen, setIsModalOpen] = useState(false);

  const { data: facility, isLoading, isError } = useFacilityDetail(facilityId);
  const { data: overview } = useFacilityOverview(facilityId);

  if (isLoading) {
    return <div className="px-8 py-8 text-base text-neutral-600">불러오는 중...</div>;
  }
  if (isError || !facility) {
    return (
      <div className="px-8 py-8 text-base text-neutral-600">시설물 정보를 불러오지 못했습니다.</div>
    );
  }

  const metaLine = [
    facility.type,
    facility.address,
    facility.builtYear && `준공 ${facility.builtYear}`,
    facility.scale,
  ]
    .filter(Boolean)
    .join(' · ');

  return (
    <>
      <FacilityOverviewPanel
        facilityName={facility.name}
        metaLine={metaLine}
        grade={overview?.overallGrade}
        totalRounds={overview?.totalRounds ?? '—'}
        cumulativeDefectCount={overview?.cumulativeDefectCount ?? '—'}
        unresolvedDefectCount={overview?.unresolvedDefectCount ?? '—'}
        nextInspectionBadge={<DueDateBadge nextInspectionDueAt={facility.nextInspectionDueAt} />}
        history={overview?.history ?? []}
        onNewInspection={() => setIsModalOpen(true)}
      />

      <InspectionCreateModal
        open={isModalOpen}
        onClose={() => setIsModalOpen(false)}
        defaultFacilityId={facilityId}
        onCreated={(response) => {
          setIsModalOpen(false);
          // 새로 만든 회차가 곧바로 보이는 시설물 상세 화면으로 이동(점검 이력 자체는 아직 목데이터).
          navigate(`/facilities/${response.facilityId}?facilityId=${response.facilityId}`);
        }}
      />
    </>
  );
}
