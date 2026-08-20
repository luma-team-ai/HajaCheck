import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { FacilityOverviewPanel } from '../../../shared/components/FacilityOverviewPanel';
import { FacilityFormModal } from '../components/FacilityFormModal';
import { InspectionCycleStatusBadge } from '../components/InspectionCycleStatusBadge';
import { useFacility } from '../hooks/useFacility';
import { useFacilityInspectionOverview } from '../hooks/useFacilityInspectionOverview';
import { useUpdateFacility } from '../hooks/useUpdateFacility';
import type { CreateFacilityRequest } from '../types';

// 시설물 상세 — Figma "hajaCheck Facility Detail - Fixed Images"(node-id 1-1401) dev mode 마크업 기준.
// 레이아웃은 features/inspection의 점검(회차) 생성 화면과 공유해 shared/FacilityOverviewPanel로 렌더링한다.
// 기본 정보(이름/유형/주소/준공년도/규모/다음 점검일)는 GET /api/facilities/{id} 실 연동,
// 점검 회차/누적 하자/미조치/점검 이력·등급은 집계 API가 아직 없어 feature 로컬 목(useFacilityInspectionOverview).
export function FacilityDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const facilityId = Number(id);
  const isInvalidId = Number.isNaN(facilityId);

  // NaN(예: 사이드바 플레이스홀더 '/facilities/detail')이면 useFacility 내부 enabled 가드로 조회
  // 자체를 시도하지 않는다(useInspectionResult와 동일 패턴) — 아래서 !facility로 에러 처리된다.
  const { data: facility, isLoading, isError } = useFacility(facilityId);
  const { data: overview } = useFacilityInspectionOverview(facilityId);

  // 시설물 수정(#1681) — PUT /api/facilities/{id}는 기존 존재(#618 useBackfillFacilityGeocode가
  // 좌표 소급 재-geocoding 용도로 이미 재사용 중), 이 화면엔 "PUT 미배선" 상태였다가 이번에 배선한다.
  const [isEditModalOpen, setIsEditModalOpen] = useState(false);
  // 등록 폼(FacilityListPage)과 동일하게 좌표 자동계산 실패는 등록/수정을 막지 않는 best-effort
  // 정책(#629)이라, 성공 시 모달이 곧 닫히므로 실패 사실을 이 페이지가 인라인 배너로 이어서 보여준다.
  const [geocodeWarningMessage, setGeocodeWarningMessage] = useState<string | undefined>(undefined);
  const {
    updateFacility,
    isPending: isUpdating,
    error: updateError,
    resetError: resetUpdateError,
  } = useUpdateFacility();

  if (isLoading) {
    return <div className="px-8 py-8 text-base text-neutral-600">불러오는 중...</div>;
  }
  if (isInvalidId || isError || !facility) {
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

  const handleOpenEditModal = () => {
    setGeocodeWarningMessage(undefined);
    setIsEditModalOpen(true);
  };
  const handleCloseEditModal = () => {
    setIsEditModalOpen(false);
    // 모달을 닫을 때(성공/실패/취소/Escape 어떤 경로든) 이전 실패의 에러를 초기화 — 그러지 않으면
    // 재오픈 시 지난 세션의 에러 메시지가 즉시 다시 노출된다(useCreateFacility 호출부와 동일 패턴).
    resetUpdateError();
  };
  // 실패는 여기서 잡지 않고 그대로 전파한다 — FacilityFormModal이 실패를 감지해 폼 값을 초기화하지
  // 않고 유지해야 하므로(수정 실패 시 사용자가 고친 내용을 잃지 않도록), 이 함수가 던지는 rejection을
  // FacilityFormModal의 handleSubmit이 catch한다(FacilityListPage.handleSubmit과 동일 패턴).
  const handleSubmitEdit = async (payload: CreateFacilityRequest) => {
    await updateFacility({ id: facilityId, body: payload });
    handleCloseEditModal();
  };
  const handleGeocodeFailure = (message: string) => {
    setGeocodeWarningMessage(message);
  };

  return (
    <>
      {geocodeWarningMessage && (
        <p role="alert" className="mx-8 mt-8 mb-0 text-sm text-warning-soft-fg">
          {geocodeWarningMessage}
        </p>
      )}
      <FacilityOverviewPanel
        facilityName={facility.name}
        metaLine={metaLine}
        grade={overview?.overallGrade}
        totalRounds={overview?.totalRounds ?? '—'}
        cumulativeDefectCount={overview?.cumulativeDefectCount ?? '—'}
        unresolvedDefectCount={overview?.unresolvedDefectCount ?? '—'}
        nextInspectionBadge={<InspectionCycleStatusBadge nextInspectionDueAt={facility.nextInspectionDueAt} />}
        history={overview?.history ?? []}
        onEdit={handleOpenEditModal}
        onNewInspection={() => navigate(`/inspections/create?facilityId=${facilityId}`)}
        // "하자 현황" 탭 클릭 → 시설물 스코프 하자 관리 목록(/defects/list?facilityId=...)으로 이동한다
        // (#1729). 종전엔 facility.latestDefectId(검수 때 "누락 추가"로 생성된 가장 최근 하자 1건)
        // 단건 드릴다운이라 검수 확정 하자 전체가 아니라 최신 하자 하나만 보이는 문제가 있었다 —
        // 사용자 결정(A안)으로 목록 화면 재배선. 대표 하자 유무와 무관하게 항상 이동하며(하자 0건이면
        // 빈 목록), 로컬 탭 폴백("준비 중인 화면입니다")은 더 이상 쓰지 않는다.
        onDefectsTabClick={() => navigate(`/defects/list?facilityId=${facilityId}`)}
        // "+N"(추가 사진) 클릭 → 분석 결과 뷰어(#1549, mypage/MyInspectionsTable "결과 보기"와 동일 경로).
        onViewInspectionPhotos={(inspectionId) => navigate(`/inspections/${inspectionId}/viewer`)}
        // "결과 보기"/"보고서"(#1359 후속) — 같은 시설물·같은 회차로 좁힌 하자 관리/보고서 목록으로 이동한다.
        onViewResult={(item) => navigate(`/defects/list?facilityId=${facilityId}&roundNoMin=${item.roundNo}&roundNoMax=${item.roundNo}`)}
        onViewReport={(item) => navigate(`/reports?facilityId=${facilityId}&roundNo=${item.roundNo}`)}
      />
      <FacilityFormModal
        open={isEditModalOpen}
        onClose={handleCloseEditModal}
        onSubmit={handleSubmitEdit}
        isSubmitting={isUpdating}
        submitErrorMessage={updateError?.message}
        onGeocodeFailure={handleGeocodeFailure}
        mode="edit"
        initialFacility={facility}
      />
    </>
  );
}
