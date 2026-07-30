import { useMutation } from '@tanstack/react-query';
import { useState } from 'react';
import type { ApiError } from '../../../shared/api/types';
import { mediaApi } from '../api/mediaApi';
import type { Media } from '../types';

interface UploadMediaInput {
  inspectionId: number;
  files: File[];
}

// inspectionId를 훅 생성 시점이 아니라 호출 시점 인자로 받는다 — 새 점검 생성 화면처럼
// 점검 회차가 폼 제출로 막 만들어져 그 응답에서만 id를 아는 경우도 같은 훅으로 처리하기 위함.
export function useUploadMedia() {
  const [progress, setProgress] = useState(0);

  const mutation = useMutation<Media[], ApiError, UploadMediaInput>({
    mutationFn: ({ inspectionId, files }) => {
      setProgress(0);
      return mediaApi.upload(inspectionId, files, setProgress).then((res) => res.data);
    },
  });

  return {
    uploadMedia: mutation.mutateAsync,
    isPending: mutation.isPending,
    error: mutation.error,
    resetError: mutation.reset,
    progress,
  };
}
