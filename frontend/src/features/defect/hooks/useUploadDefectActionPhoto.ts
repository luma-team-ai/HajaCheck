import { useMutation } from '@tanstack/react-query';
import { useState } from 'react';
import type { ApiError } from '../../../shared/api/types';
import { defectMediaApi } from '../api/defectMediaApi';
import type { DefectActionPhoto } from '../api/defectMediaApi';

interface UploadInput {
  inspectionId: number;
  file: File;
}

// 하자 상세 모달 "조치 후 사진 업로드" — inspection feature의 useUploadMedia.ts와 동일 패턴을
// defect feature 안에 자체 복제(feature 간 직접 import 금지).
export function useUploadDefectActionPhoto() {
  const [progress, setProgress] = useState(0);

  const mutation = useMutation<DefectActionPhoto[], ApiError, UploadInput>({
    mutationFn: ({ inspectionId, file }) => {
      setProgress(0);
      return defectMediaApi.uploadActionPhoto(inspectionId, file, setProgress).then((res) => res.data);
    },
  });

  return {
    uploadActionPhoto: mutation.mutateAsync,
    isPending: mutation.isPending,
    error: mutation.error,
    resetError: mutation.reset,
    progress,
  };
}
