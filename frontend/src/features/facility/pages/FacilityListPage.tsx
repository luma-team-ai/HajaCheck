import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button } from '../../../shared/components/Button';
import { FacilityCardGrid } from '../components/FacilityCardGrid';
import { FacilityFilterBar } from '../components/FacilityFilterBar';
import { FacilityFormModal } from '../components/FacilityFormModal';
import { useCreateFacility } from '../hooks/useCreateFacility';
import { useFacilities } from '../hooks/useFacilities';
import { useUploadFacilityPhotos } from '../hooks/useUploadFacilityPhotos';
import type { CreateFacilityRequest } from '../types';
import { FACILITY_LIST_FILTERS_INITIAL, filterFacilities } from '../utils/filterFacilities';
import type { FacilityListFilters } from '../utils/filterFacilities';

export function FacilityListPage() {
  const [isModalOpen, setIsModalOpen] = useState(false);
  // 주소 좌표 자동계산(Geocoder) 실패 best-effort 안내(#629, 사용자 결정) — 등록 자체는 막지 않으므로
  // 모달이 곧 닫히는 성공 경로에서도 사용자가 사실을 인지할 수 있도록 이 페이지에 인라인으로 남긴다
  // (이 레포는 별도 Toast 시스템을 두지 않는 컨벤션).
  const [geocodeWarningMessage, setGeocodeWarningMessage] = useState<string | undefined>(undefined);
  // 검색+유형/지역/등급 필터(#810) — 백엔드 변경 없이 클라이언트 사이드로 처리(utils/filterFacilities.ts).
  const [filters, setFilters] = useState<FacilityListFilters>(FACILITY_LIST_FILTERS_INITIAL);
  const navigate = useNavigate();
  const { data: facilities, isLoading, isError, refetch } = useFacilities();
  const { createFacility, isPending, error, resetError } = useCreateFacility();
  const { uploadPhotos } = useUploadFacilityPhotos();

  const isFilterActive =
    filters.search !== '' || filters.type !== '' || filters.region !== '' || filters.grade !== '';
  const filteredFacilities = useMemo(
    () => (facilities ? filterFacilities(facilities, filters) : facilities),
    [facilities, filters],
  );

  const handleOpenModal = () => {
    setGeocodeWarningMessage(undefined);
    setIsModalOpen(true);
  };
  const handleCloseModal = () => {
    setIsModalOpen(false);
    // 모달을 닫을 때(성공/실패/취소/Escape 등 어떤 경로든) 이전 실패의 에러를 초기화 —
    // 그러지 않으면 재오픈 시 지난 세션의 에러 메시지가 즉시 다시 노출된다.
    resetError();
  };

  // 시설물 이름 클릭 → 하자 오버레이(HAJA-434 갭1, Figma node-id=1-3958 흐름)로 직행.
  // 대표 하자가 없는 시설물(하자 미탐지)은 갈 곳이 없으므로 기존 시설물 상세로 폴백한다.
  const handleSelectFacility = (id: number, latestDefectId: number | null) => {
    navigate(
      latestDefectId != null ? `/facilities/${id}/defects/${latestDefectId}` : `/facilities/${id}`,
    );
  };

  const handleSubmit = async (payload: CreateFacilityRequest, photos: File[]) => {
    // 실패 시 여기서 잡지 않고 그대로 전파한다 — FacilityFormModal이 실패를 감지해 폼 값을
    // 초기화하지 않고 유지해야 하므로(등록 실패 시 사용자가 입력한 내용을 잃지 않도록),
    // 이 함수가 던지는 rejection을 FacilityFormModal의 handleSubmit이 catch한다.
    const facility = await createFacility(payload);
    // 사진 업로드는 시설물 생성이 성공한 뒤에만 가능하다(POST /api/facilities/{id}/media, #652) —
    // 사진을 선택하지 않았으면 호출하지 않는다.
    // 알려진 제한사항(이번 스코프에서 자동 처리 안 함): 시설물 생성 성공 후 사진 업로드만 실패하면
    // FacilityFormModal의 catch가 폼을 유지하지만 시설물은 이미 생성된 상태라, 사용자가 그대로
    // 재제출하면 중복 시설물이 생길 수 있다. 이 케이스를 구분하는 전용 에러 처리(예: "시설물은
    // 등록됐지만 사진 업로드는 실패했습니다" 별도 안내)는 후속 이슈로 남긴다.
    if (photos.length > 0) {
      await uploadPhotos(facility.id, photos);
    }
    handleCloseModal();
  };

  // 주소 좌표 자동계산(Geocoder) 실패 best-effort 안내(#629) — 등록은 이미 좌표 없이 진행되므로
  // 여기서는 사용자에게 사실을 알리는 인라인 배너만 세팅한다.
  const handleGeocodeFailure = (message: string) => {
    setGeocodeWarningMessage(message);
  };

  return (
    <div className="mx-auto flex max-w-6xl flex-col gap-6 px-6 py-8">
      <div className="flex items-center justify-between">
        <h1 className="m-0 flex items-center gap-2 text-xl font-bold text-heading">
          시설물 관리
          {facilities && <span className="text-base font-normal text-text-muted">{facilities.length}</span>}
        </h1>
        <div className="flex items-center gap-2">
          <Button variant="primary" onClick={handleOpenModal}>
            + 시설물 등록
          </Button>
        </div>
      </div>

      {geocodeWarningMessage && (
        <p role="alert" className="m-0 text-sm text-warning-soft-fg">
          {geocodeWarningMessage}
        </p>
      )}

      <FacilityFilterBar facilities={facilities ?? []} filters={filters} onChange={setFilters} />

      <FacilityCardGrid
        facilities={filteredFacilities}
        isLoading={isLoading}
        isError={isError}
        onRetry={refetch}
        onSelectFacility={handleSelectFacility}
        emptyMessage={
          isFilterActive
            ? '검색/필터 조건에 맞는 시설물이 없습니다.'
            : '등록된 시설물이 없습니다. 시설물을 등록해 주세요.'
        }
      />

      <FacilityFormModal
        open={isModalOpen}
        onClose={handleCloseModal}
        onSubmit={handleSubmit}
        isSubmitting={isPending}
        submitErrorMessage={error?.message}
        onGeocodeFailure={handleGeocodeFailure}
      />
    </div>
  );
}
