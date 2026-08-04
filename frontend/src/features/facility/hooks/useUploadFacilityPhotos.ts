import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import type { ApiError } from '../../../shared/api/types';
import { facilityMediaApi } from '../api/facilityMediaApi';
import type { FacilityPhoto } from '../types';
import { facilityKeys } from './useFacilities';

interface UploadFacilityPhotosInput {
  facilityId: number;
  files: File[];
}

// inspection/hooks/useUploadMedia.ts와 동일 패턴 — facilityId를 훅 생성 시점이 아니라 호출 시점
// 인자로 받는다. 시설물 등록 폼은 시설물이 막 생성된 응답(FacilityListPage.handleSubmit)에서만
// facilityId를 알 수 있으므로, 훅을 미리 특정 facilityId에 묶어둘 수 없다(#652).
export function useUploadFacilityPhotos() {
  const [progress, setProgress] = useState(0);
  const queryClient = useQueryClient();

  const mutation = useMutation<FacilityPhoto[], ApiError, UploadFacilityPhotosInput>({
    mutationFn: ({ facilityId, files }) => {
      setProgress(0);
      return facilityMediaApi.upload(facilityId, files, setProgress).then((res) => res.data);
    },
    // 시설물 생성(useCreateFacility)이 목록 캐시를 무효화하는 시점은 사진 업로드 "전"이라 그 응답엔
    // 썸네일이 없다. 업로드가 별도 mutation이라 자체 onSuccess가 없으면, 실제로 사진이 붙은 뒤에도
    // 목록 캐시가 갱신되지 않아 등록 직후 화면에 "사진 없음"이 고정 표시된다(새 페이지로 이동했다가
    // 돌아와야만 staleTime=0 기본값 덕에 우연히 재조회돼 보이던 문제). 업로드 성공 시점에도 목록을
    // 무효화해 캐시가 정합하게 한다.
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: facilityKeys.list });
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
