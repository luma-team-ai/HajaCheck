import { useState } from 'react';
import { Button } from '../../../shared/components/Button';
import { FacilityFormModal } from '../components/FacilityFormModal';
import { FacilityTable } from '../components/FacilityTable';
import { useCreateFacility } from '../hooks/useCreateFacility';
import { useFacilities } from '../hooks/useFacilities';
import type { CreateFacilityRequest } from '../types';

export function FacilityListPage() {
  const [isModalOpen, setIsModalOpen] = useState(false);
  const { data: facilities, isLoading, isError, refetch } = useFacilities();
  const { createFacility, isPending, error } = useCreateFacility();

  const handleOpenModal = () => setIsModalOpen(true);
  const handleCloseModal = () => setIsModalOpen(false);

  const handleSubmit = async (payload: CreateFacilityRequest) => {
    try {
      await createFacility(payload);
      handleCloseModal();
    } catch {
      // 등록 실패 시 모달을 닫지 않고 유지 — 에러 메시지는 useCreateFacility의 error(ApiError)를
      // submitErrorMessage로 전달해 모달 내부에 표시한다(여기서 추가 처리 불필요).
    }
  };

  return (
    <div className="mx-auto flex max-w-6xl flex-col gap-6 px-6 py-8">
      <div className="flex items-center justify-between">
        <h1 className="m-0 flex items-center gap-2 text-xl font-bold text-heading">
          시설물 관리
          {facilities && <span className="text-base font-normal text-text-muted">{facilities.length}</span>}
        </h1>
        <Button variant="primary" onClick={handleOpenModal}>
          + 시설물 등록
        </Button>
      </div>

      <div className="overflow-hidden rounded-2xl border border-border bg-surface">
        <FacilityTable
          facilities={facilities}
          isLoading={isLoading}
          isError={isError}
          onRetry={refetch}
        />
      </div>

      <FacilityFormModal
        open={isModalOpen}
        onClose={handleCloseModal}
        onSubmit={handleSubmit}
        isSubmitting={isPending}
        submitErrorMessage={error?.message}
      />
    </div>
  );
}
