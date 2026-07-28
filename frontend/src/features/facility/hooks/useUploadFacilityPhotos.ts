import { useMutation } from '@tanstack/react-query';
import { useState } from 'react';
import type { ApiError } from '../../../shared/api/types';
import { facilityMediaApi } from '../api/facilityMediaApi';
import type { FacilityPhoto } from '../types';

interface UploadFacilityPhotosInput {
  facilityId: number;
  files: File[];
}

// inspection/hooks/useUploadMedia.ts와 동일 패턴 — facilityId를 훅 생성 시점이 아니라 호출 시점
// 인자로 받는다. 시설물 등록 폼은 시설물이 막 생성된 응답(FacilityListPage.handleSubmit)에서만
// facilityId를 알 수 있으므로, 훅을 미리 특정 facilityId에 묶어둘 수 없다(#652).
export function useUploadFacilityPhotos() {
  const [progress, setProgress] = useState(0);

  const mutation = useMutation<FacilityPhoto[], ApiError, UploadFacilityPhotosInput>({
    mutationFn: ({ facilityId, files }) => {
      setProgress(0);
      return facilityMediaApi.upload(facilityId, files, setProgress).then((res) => res.data);
    },
  });

  return {
    uploadPhotos: (facilityId: number, files: File[]) => mutation.mutateAsync({ facilityId, files }),
    isPending: mutation.isPending,
    error: mutation.error,
    resetError: mutation.reset,
    progress,
  };
}
