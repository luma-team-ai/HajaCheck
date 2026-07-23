import type { ChangeEvent } from 'react';
import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Button } from '../../../shared/components/Button';
import { useAuthStore } from '../../auth/store/authStore';
import { inspectionApi } from '../api/inspectionApi';
import type { StagedMediaFile } from '../components/InspectionMediaUploadPanel';
import { InspectionMediaUploadPanel } from '../components/InspectionMediaUploadPanel';
import { useCreateInspection } from '../hooks/useCreateInspection';
import { useFacilityOptions } from '../hooks/useFacilityOptions';
import { useUploadMedia } from '../hooks/useUploadMedia';
import type { InspectionCreateResponse } from '../types';
import {
  classifyMediaFile,
  exceedsMaxFileCount,
  formatFileSize,
  validateMediaFile,
  validateVideoFile,
} from '../utils/validateMediaFiles';
import type {
  InspectionCreateFormErrors,
  InspectionCreateFormValues,
} from '../utils/validateInspectionCreateForm';
import {
  INSPECTION_CREATE_FORM_INITIAL_VALUES,
  hasInspectionCreateFormErrors,
  toInspectionCreateRequest,
  validateInspectionCreateForm,
} from '../utils/validateInspectionCreateForm';

const INPUT_CLASSES =
  'w-full rounded-full border border-border bg-white px-4 py-2.5 text-base text-text-default outline-none focus:ring-2 focus:ring-primary';
const LABEL_CLASSES = 'text-xs font-medium tracking-wide text-text-muted';
const ERROR_CLASSES = 'text-xs text-danger';

// 새 점검 생성 — 회의 후 반영된 시안(2026-07-22): 기존 "시설물 개요 + 모달" 2단계 플로우를
// 폐지하고, 점검 정보 입력과 촬영 데이터 업로드를 한 화면에서 처리한다(AP-004+AP-005 통합 화면).
// 제출 시 ① POST /api/inspections로 회차 생성 ② 응답 id로 이미지 파일만 POST .../media 업로드
// (영상은 아직 백엔드 업로드 대상이 아님) ③ POST .../analyze로 AI 분석 시작(dev-05-04, 202 즉시
// 반환) ④ AI 분석 실행/상태 화면(/inspections/{id}/analysis)으로 이동 — 그 화면이 실제 진행률을
// 폴링한다.
export function InspectionCreatePage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const currentUser = useAuthStore((state) => state.user);
  const { data: facilities, isLoading: isFacilitiesLoading } = useFacilityOptions();
  const {
    createInspection,
    isPending: isCreating,
    error: createError,
    resetError: resetCreateError,
  } = useCreateInspection();
  const {
    uploadMedia,
    isPending: isUploading,
    progress: uploadProgress,
    error: uploadError,
  } = useUploadMedia();

  // 시설물 상세 "+새 점검"(FacilityDetailPage)이 ?facilityId=로 넘겨준 값이 있으면 시설물
  // 셀렉트 초기값으로 채운다 — 방금 보던 시설물을 다시 고르는 수고를 없앤다.
  const [values, setValues] = useState<InspectionCreateFormValues>(() => ({
    ...INSPECTION_CREATE_FORM_INITIAL_VALUES,
    facilityId: searchParams.get('facilityId') ?? INSPECTION_CREATE_FORM_INITIAL_VALUES.facilityId,
  }));
  const [errors, setErrors] = useState<InspectionCreateFormErrors>({});
  // 메모 — 시안에는 있으나 backend InspectionCreateRequest 계약에 아직 필드가 없어(facilityId·
  // inspectionDate·assignedInspectorId만 존재) 로컬 상태로만 유지한다. 후속 계약 확장 시 배선.
  const [memo, setMemo] = useState('');
  const [mediaFiles, setMediaFiles] = useState<StagedMediaFile[]>([]);
  const [uploadDone, setUploadDone] = useState(false);
  const [fileCountError, setFileCountError] = useState<string | null>(null);
  // 회차 생성(POST /api/inspections)이 성공한 뒤 업로드가 실패하면 여기 보관해둔다 — 재제출 시
  // createInspection을 다시 호출하지 않고 이 id로 업로드만 재시도해 회차 중복 생성을 막는다(P1).
  const [createdInspection, setCreatedInspection] = useState<InspectionCreateResponse | null>(
    null,
  );

  const isSubmitting = isCreating || isUploading;
  const hasFileErrors = mediaFiles.some((entry) => entry.error !== null);
  const totalSize = mediaFiles.reduce((sum, entry) => sum + entry.file.size, 0);
  // 회차가 이미 생성된 뒤에는 점검 정보를 바꿔도 반영되지 않는다(재시도는 업로드만 재실행) —
  // 혼동을 막기 위해 입력을 잠근다.
  const isFieldsLocked = isSubmitting || createdInspection !== null;

  const handleFieldChange =
    (field: keyof InspectionCreateFormValues) =>
    (event: ChangeEvent<HTMLInputElement | HTMLSelectElement>) => {
      setValues((prev) => ({ ...prev, [field]: event.target.value }));
      resetCreateError();
    };

  const handleFilesAdd = (newFiles: File[]) => {
    setUploadDone(false);
    const staged: StagedMediaFile[] = newFiles.map((file) => {
      const kind = classifyMediaFile(file);
      if (!kind) {
        return { file, kind: 'image', error: 'FILE_INVALID_TYPE' };
      }
      const error = kind === 'image' ? validateMediaFile(file) : validateVideoFile(file);
      return { file, kind, error };
    });

    // 개수 상한은 실제 업로드 대상(이미지)에만 적용 — 영상은 요청에 포함되지 않는다.
    const currentImageCount = mediaFiles.filter((entry) => entry.kind === 'image').length;
    const addingImageCount = staged.filter((entry) => entry.kind === 'image').length;
    if (exceedsMaxFileCount(currentImageCount, addingImageCount)) {
      setFileCountError('이미지는 한 번에 최대 10개까지 업로드할 수 있습니다.');
      return;
    }
    setFileCountError(null);
    setMediaFiles((prev) => [...prev, ...staged]);
  };

  const handleRemoveFile = (index: number) => {
    setMediaFiles((prev) => prev.filter((_, i) => i !== index));
  };

  const handleSubmit = async () => {
    const nextErrors = validateInspectionCreateForm(values);
    setErrors(nextErrors);
    if (hasInspectionCreateFormErrors(nextErrors) || hasFileErrors || !currentUser) {
      return;
    }

    try {
      // 이미 생성된 회차가 있으면(직전 시도에서 업로드만 실패) 재생성하지 않고 재사용 — 안 그러면
      // 재제출마다 같은 시설물/점검일/담당자로 orphan 점검 회차가 계속 쌓인다.
      // 담당 점검자는 더 이상 수동 입력을 받지 않고 로그인한 본인으로 자동 배정한다 — 본인이
      // 회사 소속 INSPECTOR/ADMIN이 아니면 백엔드 검증(AuthService.validateAssignableInspector)에서
      // AUTH_INVALID_INSPECTOR로 거부되고, 아래 createError로 그대로 노출된다.
      const inspection =
        createdInspection ??
        (await createInspection(toInspectionCreateRequest(values, currentUser.id)));
      if (!createdInspection) {
        setCreatedInspection(inspection);
      }

      const imageFiles = mediaFiles
        .filter((entry) => entry.kind === 'image' && !entry.error)
        .map((entry) => entry.file);
      if (imageFiles.length > 0) {
        await uploadMedia({ inspectionId: inspection.id, files: imageFiles });
        setUploadDone(true);
      }

      // AI 분석 트리거(POST /api/inspections/{id}/analyze, dev-05-04) — 202로 바로 반환되는
      // 비동기 잡이라 완료를 기다리지 않는다. 실패해도(예: 네트워크 순간 오류) 이동은 그대로
      // 진행한다 — 상태 화면이 "분석 대기" 그대로를 보여줄 뿐 사용자가 재시도할 여지가 남는다.
      try {
        await inspectionApi.startAnalysis(inspection.id);
      } catch {
        // 아래 catch와 별개로 조용히 무시 — 회차 생성·업로드는 이미 성공했으므로 이동은 계속한다.
      }
      navigate(`/inspections/${inspection.id}/analysis`);
    } catch {
      // 실패 사유는 error로 아래에 표시 — 입력값·선택 파일은 유지해 재시도 가능하게 둔다.
      // 회차 생성까지는 성공했다면 createdInspection에 남아있어 다음 제출은 업로드만 재시도한다.
    }
  };

  return (
    <div className="flex flex-col gap-6 px-8 py-8">
      <div className="flex flex-col gap-8 rounded-[20px] bg-white px-8 py-8 shadow-sm outline outline-1 outline-offset-[-1px] outline-neutral-300/20">
        <section className="flex flex-col gap-4">
          <h1 className="m-0 text-xl font-medium text-zinc-900">점검 정보</h1>
          <div className="grid grid-cols-1 gap-4 border-t border-neutral-300/20 pt-4 sm:grid-cols-2 lg:grid-cols-4">
            <div className="flex flex-col gap-1.5">
              <label htmlFor="inspection-facility" className={LABEL_CLASSES}>
                시설물
              </label>
              <select
                id="inspection-facility"
                value={values.facilityId}
                onChange={handleFieldChange('facilityId')}
                className={INPUT_CLASSES}
                disabled={isFacilitiesLoading || isFieldsLocked}
                aria-invalid={Boolean(errors.facilityId)}
                aria-describedby={errors.facilityId ? 'inspection-facility-error' : undefined}
              >
                <option value="">
                  {isFacilitiesLoading ? '불러오는 중...' : '시설물을 선택하세요'}
                </option>
                {facilities?.map((facility) => (
                  <option key={facility.id} value={facility.id}>
                    {facility.name}
                  </option>
                ))}
              </select>
              {errors.facilityId && (
                <p id="inspection-facility-error" className={ERROR_CLASSES}>
                  {errors.facilityId}
                </p>
              )}
            </div>

            <div className="flex flex-col gap-1.5">
              <label htmlFor="inspection-date" className={LABEL_CLASSES}>
                점검일
              </label>
              <input
                id="inspection-date"
                type="date"
                value={values.inspectionDate}
                onChange={handleFieldChange('inspectionDate')}
                className={INPUT_CLASSES}
                disabled={isFieldsLocked}
                aria-invalid={Boolean(errors.inspectionDate)}
                aria-describedby={errors.inspectionDate ? 'inspection-date-error' : undefined}
              />
              {errors.inspectionDate && (
                <p id="inspection-date-error" className={ERROR_CLASSES}>
                  {errors.inspectionDate}
                </p>
              )}
            </div>

            <div className="flex flex-col gap-1.5">
              <label htmlFor="inspection-memo" className={LABEL_CLASSES}>
                메모
              </label>
              <input
                id="inspection-memo"
                type="text"
                placeholder="점검 관련 메모(선택)"
                value={memo}
                onChange={(event) => setMemo(event.target.value)}
                className={INPUT_CLASSES}
                disabled={isFieldsLocked}
              />
            </div>
          </div>

          {createError && (
            <p role="alert" className={ERROR_CLASSES}>
              {createError.message ?? '점검 회차 생성에 실패했습니다.'}
            </p>
          )}
        </section>

        <section className="flex flex-col gap-4">
          <h2 className="m-0 text-xl font-medium text-zinc-900">데이터 업로드</h2>
          <InspectionMediaUploadPanel
            files={mediaFiles}
            onFilesAdd={handleFilesAdd}
            onRemove={handleRemoveFile}
            uploaded={uploadDone}
            uploadProgress={isUploading ? uploadProgress : null}
            disabled={isSubmitting}
          />
          {fileCountError && (
            <p role="alert" className={ERROR_CLASSES}>
              {fileCountError}
            </p>
          )}
          {uploadError && (
            <p role="alert" className={ERROR_CLASSES}>
              {uploadError.message ?? '업로드에 실패했습니다.'}
            </p>
          )}
        </section>

        <div className="flex items-center justify-between gap-4 rounded-full border border-neutral-300/30 bg-surface-muted px-6 py-3">
          <span className="text-sm text-text-muted">
            {mediaFiles.length > 0
              ? `총 ${mediaFiles.length}개 파일 · ${formatFileSize(totalSize)}`
              : '아직 첨부된 파일이 없습니다'}
          </span>
          <div className="flex items-center gap-3">
            <Button
              type="button"
              variant="secondary"
              disabled
              title="임시저장은 준비 중입니다"
            >
              임시저장
            </Button>
            <Button
              type="button"
              variant="primary"
              onClick={handleSubmit}
              disabled={isSubmitting || hasFileErrors}
            >
              {isSubmitting ? '처리 중...' : '업로드 완료 후 AI 분석 시작'}
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}
