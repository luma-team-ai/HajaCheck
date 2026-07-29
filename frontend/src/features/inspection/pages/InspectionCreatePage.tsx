import type { ChangeEvent } from 'react';
import { useEffect, useRef, useState } from 'react';
import { flushSync } from 'react-dom';
import { useBlocker, useNavigate, useSearchParams } from 'react-router-dom';
import { Button } from '../../../shared/components/Button';
import { Modal } from '../../../shared/components/Modal';
import { useAuthStore } from '../../auth/store/authStore';
import { inspectionApi } from '../api/inspectionApi';
import { useInspectionStore } from '../store/inspectionStore';
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
import {
  clearInspectionCreateDraft,
  loadInspectionCreateDraft,
  saveInspectionCreateDraft,
} from '../utils/inspectionCreateDraft';
import {
  clearDraftMediaFiles,
  loadDraftMediaFiles,
  saveDraftMediaFiles,
} from '../utils/inspectionCreateDraftFiles';

const INPUT_CLASSES =
  'w-full rounded-full border border-border bg-white px-4 py-2.5 text-base text-text-default outline-none focus:ring-2 focus:ring-primary';
const LABEL_CLASSES = 'text-xs font-medium tracking-wide text-text-muted';
// SideNavBar의 "구현되지 않은 페이지" 안내와 동일한 자동 소멸 시간(#217 컨벤션 재사용)
const DRAFT_SAVED_NOTICE_MS = 2500;
const ERROR_CLASSES = 'text-xs text-danger';

// File[] → StagedMediaFile[] 분류/검증 — handleFilesAdd(신규 선택)와 임시저장 복원(기존 File 재구성) 둘 다 사용.
function stageMediaFiles(files: File[]): StagedMediaFile[] {
  return files.map((file) => {
    const kind = classifyMediaFile(file);
    if (!kind) {
      return { file, kind: 'image', error: 'FILE_INVALID_TYPE' };
    }
    const error = kind === 'image' ? validateMediaFile(file) : validateVideoFile(file);
    return { file, kind, error };
  });
}

interface ActiveRound {
  id: number;
  roundNo: number;
}

// 같은 시설물에 미종료(REPORTED 아님) 회차가 이미 있으면 그중 가장 최근 회차를 반환한다 —
// 실수로 중복 회차를 만드는 걸 막기 위한 "약한" 경고용(확인창만 띄우고 생성 자체를 막지는 않는다).
// id까지 반환하는 이유(팀 리뷰 반영, 2026-07-28): 예전엔 roundNo만 반환해 확인창이 "새로 만들까/
// 취소할까"만 물었는데, 그 회차로 바로 돌아갈 방법(이어서 하기)이 없어 브라우저를 한 번만 벗어나도
// 진행 중이던 회차를 사실상 잃어버렸다(activeInspectionId가 메모리 전용이라 새로고침 시 소실).
// 조회 실패는 경고 없이 그냥 진행시킨다(부가 안내일 뿐, 생성 흐름을 막을 이유가 아니다).
async function findActiveRound(facilityId: number): Promise<ActiveRound | null> {
  try {
    const res = await inspectionApi.listByFacility(facilityId);
    const activeRounds = res.data.content.filter((item) => item.status !== 'REPORTED');
    if (activeRounds.length === 0) return null;
    return activeRounds.reduce((latest, item) => (item.roundNo > latest.roundNo ? item : latest));
  } catch {
    return null;
  }
}

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
  const setActiveInspectionId = useInspectionStore((state) => state.setActiveInspectionId);
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
  // 셀렉트 초기값으로 채운다 — 방금 보던 시설물을 다시 고르는 수고를 없앤다. 그 다음 우선순위로
  // 임시저장된 이전 입력(sessionStorage)이 있으면 복원한다 — 명시적 딥링크(쿼리파라미터)가 항상 우선.
  const inspectionCreateDraft = useState(() => loadInspectionCreateDraft())[0];
  const [values, setValues] = useState<InspectionCreateFormValues>(() => ({
    facilityId:
      searchParams.get('facilityId') ??
      inspectionCreateDraft?.facilityId ??
      INSPECTION_CREATE_FORM_INITIAL_VALUES.facilityId,
    inspectionDate:
      inspectionCreateDraft?.inspectionDate ?? INSPECTION_CREATE_FORM_INITIAL_VALUES.inspectionDate,
    inspectionType:
      inspectionCreateDraft?.inspectionType ?? INSPECTION_CREATE_FORM_INITIAL_VALUES.inspectionType,
  }));
  const [errors, setErrors] = useState<InspectionCreateFormErrors>({});
  // 메모 — 시안에는 있으나 backend InspectionCreateRequest 계약에 아직 필드가 없어(facilityId·
  // inspectionDate·assignedInspectorId만 존재) 로컬 상태로만 유지한다. 후속 계약 확장 시 배선.
  const [memo, setMemo] = useState(() => inspectionCreateDraft?.memo ?? '');
  const [mediaFiles, setMediaFiles] = useState<StagedMediaFile[]>([]);
  // 사이드바로 페이지를 이탈하려 할 때 확정 성공 후의 프로그램적 navigate까지 막지 않기 위한 플래그.
  const hasSubmittedRef = useRef(false);
  // 마운트 시 IndexedDB 복원이 끝나기 전까지 저장 effect가 mediaFiles 초기값([])으로 먼저 덮어쓰지
  // 못하게 막는 게이트(PR 리뷰 P2) — 복원·저장이 각자 별도 IndexedDB 커넥션/트랜잭션을 열어서,
  // 게이트 없이는 어느 쪽이 먼저 커밋될지가 브라우저 구현에 암묵적으로 의존하게 된다.
  const hasHydratedMediaRef = useRef(false);
  const [uploadDone, setUploadDone] = useState(false);
  const [fileCountError, setFileCountError] = useState<string | null>(null);
  // 회차 생성(POST /api/inspections)이 성공한 뒤 업로드가 실패하면 여기 보관해둔다 — 재제출 시
  // createInspection을 다시 호출하지 않고 이 id로 업로드만 재시도해 회차 중복 생성을 막는다(P1).
  const [createdInspection, setCreatedInspection] = useState<InspectionCreateResponse | null>(
    null,
  );
  // 코드 리뷰 P3 — currentUser 미로딩 시 제출을 조용히 막던 것 대신 사유를 보여준다.
  const [submitBlockedMessage, setSubmitBlockedMessage] = useState<string | null>(null);
  // "임시저장" 버튼 수동 클릭 피드백 — 값 자체는 이미 아래 effect가 입력마다 자동 저장하므로,
  // 이 버튼은 저장을 트리거한다기보다 "지금 저장됐다"는 확인을 사용자에게 보여주는 역할이다.
  const [showDraftSavedNotice, setShowDraftSavedNotice] = useState(false);
  const draftSavedNoticeTimerRef = useRef<ReturnType<typeof setTimeout>>();
  // 같은 시설물에 이미 진행 중인 회차가 있으면 그 회차 정보를 담아 확인창을 띄운다(null이면 안 뜸).
  const [duplicateActiveRound, setDuplicateActiveRound] = useState<ActiveRound | null>(null);
  const [isCheckingDuplicateRound, setIsCheckingDuplicateRound] = useState(false);

  const isSubmitting = isCreating || isUploading || isCheckingDuplicateRound;
  const hasFileErrors = mediaFiles.some((entry) => entry.error !== null);
  // handleSubmit이 실제로 업로드하는 대상은 kind==='image'(에러 없는 것)뿐이다(아래 handleSubmit의
  // imageFiles 필터와 동일 기준) — 영상은 업로드 대상이 아니므로 mediaFiles.length만 보면 영상만
  // 첨부한 경우를 놓친다(PR #882 리뷰 P2). 시설물·점검일·업로드 대상 이미지 중 하나라도 비어 있으면
  // 제출 자체를 막는다 — 예전엔 이미지 미첨부여도 handleSubmit이 업로드를 건너뛰고 바로 AI 분석을
  // 트리거해 빈 회차가 생성될 수 있었다.
  const hasUploadableImage = mediaFiles.some((entry) => entry.kind === 'image' && !entry.error);
  const hasMissingRequiredInput =
    !values.facilityId || !values.inspectionDate || !hasUploadableImage;
  const totalSize = mediaFiles.reduce((sum, entry) => sum + entry.file.size, 0);
  // 회차가 이미 생성된 뒤에는 점검 정보를 바꿔도 반영되지 않는다(재시도는 업로드만 재실행) —
  // 혼동을 막기 위해 입력을 잠근다.
  const isFieldsLocked = isSubmitting || createdInspection !== null;

  // 입력란 중 하나라도 채워졌으면(파일 첨부 포함) "작성 중"으로 간주 — 사이드바 이탈 시 확인창을 띄운다.
  const hasDraftInput =
    values.facilityId !== '' ||
    values.inspectionDate !== '' ||
    memo.trim() !== '' ||
    mediaFiles.length > 0;

  // 텍스트 입력(facilityId/inspectionDate/memo)은 sessionStorage에 임시저장한다.
  useEffect(() => {
    if (hasSubmittedRef.current) {
      return;
    }
    if (!hasDraftInput) {
      clearInspectionCreateDraft();
      return;
    }
    saveInspectionCreateDraft({
      facilityId: values.facilityId,
      inspectionDate: values.inspectionDate,
      inspectionType: values.inspectionType,
      memo,
    });
  }, [values.facilityId, values.inspectionDate, values.inspectionType, memo, hasDraftInput]);

  // 마운트 시 1회 — 텍스트 초안이 있을 때만(=같은 세션) IndexedDB의 첨부 파일을 복원한다.
  // 텍스트 초안이 없다면(탭을 새로 열었거나 sessionStorage가 이미 소거된 상태) IndexedDB에
  // 남아있는 파일은 이전 세션의 고아 데이터이므로 복원하지 않고 정리한다. 완료 시(성공/실패 모두)
  // hasHydratedMediaRef를 세워 아래 저장 effect가 그 전엔 mediaFiles 초기값([])으로 먼저
  // 덮어쓰지 못하게 한다.
  useEffect(() => {
    if (!inspectionCreateDraft) {
      void clearDraftMediaFiles().finally(() => {
        hasHydratedMediaRef.current = true;
      });
      return;
    }
    void loadDraftMediaFiles()
      .then((files) => {
        if (files.length > 0) {
          setMediaFiles(stageMediaFiles(files));
        }
      })
      .finally(() => {
        hasHydratedMediaRef.current = true;
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 마운트 시 1회만 실행
  }, []);

  // 첨부 파일(Blob)은 sessionStorage 용량을 훌쩍 넘길 수 있어 IndexedDB에 별도 저장한다.
  // 위 복원 effect가 끝나기 전에는(hasHydratedMediaRef.current === false) 쓰지 않는다 — 마운트
  // 직후 mediaFiles 초기값([])으로 먼저 저장해버리면 복원 대상 파일을 지울 수 있다(PR 리뷰 P2).
  useEffect(() => {
    if (hasSubmittedRef.current || !hasHydratedMediaRef.current) {
      return;
    }
    void saveDraftMediaFiles(mediaFiles.map((entry) => entry.file));
  }, [mediaFiles]);

  // 언마운트 시 대기 중인 "임시저장됨" 안내 타이머 정리(SideNavBar notice와 동일 패턴).
  useEffect(() => {
    return () => clearTimeout(draftSavedNoticeTimerRef.current);
  }, []);

  const handleManualDraftSave = () => {
    saveInspectionCreateDraft({
      facilityId: values.facilityId,
      inspectionDate: values.inspectionDate,
      inspectionType: values.inspectionType,
      memo,
    });
    setShowDraftSavedNotice(true);
    clearTimeout(draftSavedNoticeTimerRef.current);
    draftSavedNoticeTimerRef.current = setTimeout(
      () => setShowDraftSavedNotice(false),
      DRAFT_SAVED_NOTICE_MS,
    );
  };

  // 사이드바 Link 클릭 등 라우터 내부 이동을 가로챈다 — 제출 성공 후 분석 화면으로의 프로그램적
  // navigate는 hasSubmittedRef로 예외 처리(성공 직후 같은 확인창이 다시 뜨는 오동작 방지).
  const blocker = useBlocker(
    ({ currentLocation, nextLocation }) =>
      !hasSubmittedRef.current &&
      hasDraftInput &&
      currentLocation.pathname !== nextLocation.pathname,
  );
  // "나가기" 클릭 시 모달을 닫는 렌더와, blocker.proceed()가 유발하는 목적지 페이지 마운트 렌더가
  // React 배치로 한 커밋에 묶이면 목적지가 무거울 때(통계 대시보드 등) 그 렌더가 끝날 때까지 모달이
  // 화면에 남아있는 것처럼 보인다. flushSync로 모달 제거만 별도 커밋으로 강제 분리해 즉시 사라지게 한다.
  // isLeaving을 다시 false로 되돌리는 코드는 없다(PR #882 리뷰 P3 확인 요청) — @remix-run/router
  // proceed()는 상태를 'proceeding'으로 동기 전환한 뒤 같은 navigate() 호출을 재실행하고,
  // shouldBlockNavigation은 blocker.state==='proceeding'일 때 블로커 재확인 자체를 건너뛴다
  // (node_modules/@remix-run/router/dist/router.js의 proceed()·shouldBlockNavigation 구현 확인,
  // 6.30.4). 즉 이 앱처럼 블로커가 하나뿐이고 pathname이 실제로 달라야만 블로킹되는 구성에서는
  // proceed() 이후 반드시 다른 라우트로 네비게이션이 완료되어 이 컴포넌트가 언마운트되므로,
  // isLeaving이 true인 채로 blocker.state가 다시 'blocked'가 되는 경우는 발생하지 않는다.
  const [isLeaving, setIsLeaving] = useState(false);
  const handleConfirmLeave = () => {
    flushSync(() => setIsLeaving(true));
    blocker.proceed?.();
  };

  const handleFieldChange =
    (field: keyof InspectionCreateFormValues) =>
    (event: ChangeEvent<HTMLInputElement | HTMLSelectElement>) => {
      setValues((prev) => ({ ...prev, [field]: event.target.value }));
      resetCreateError();
    };

  const handleFilesAdd = (newFiles: File[]) => {
    setUploadDone(false);
    const staged = stageMediaFiles(newFiles);

    // 개수 상한은 실제 업로드 대상(이미지)에만 적용 — 영상은 요청에 포함되지 않는다.
    const currentImageCount = mediaFiles.filter((entry) => entry.kind === 'image').length;
    const addingImageCount = staged.filter((entry) => entry.kind === 'image').length;
    if (exceedsMaxFileCount(currentImageCount, addingImageCount)) {
      setFileCountError('이미지는 한 번에 최대 50개까지 업로드할 수 있습니다.');
      return;
    }
    setFileCountError(null);
    setMediaFiles((prev) => [...prev, ...staged]);
  };

  const handleRemoveFile = (index: number) => {
    setMediaFiles((prev) => prev.filter((_, i) => i !== index));
  };

  const submitInspection = async () => {
    if (!currentUser) return; // handleSubmit이 이미 검증 — 방어적 재확인만.

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
      setActiveInspectionId(inspection.id);
      // 이 지점부터는 폼을 "작성 중"으로 취급하지 않는다 — 아래 navigate가 useBlocker에 다시
      // 걸려 방금 성공한 흐름에서 이탈 확인창이 뜨는 걸 막고, 임시저장된 초안도 정리한다.
      hasSubmittedRef.current = true;
      clearInspectionCreateDraft();
      void clearDraftMediaFiles();
      navigate(`/inspections/${inspection.id}/analysis`);
    } catch {
      // 실패 사유는 error로 아래에 표시 — 입력값·선택 파일은 유지해 재시도 가능하게 둔다.
      // 회차 생성까지는 성공했다면 createdInspection에 남아있어 다음 제출은 업로드만 재시도한다.
    }
  };

  const handleSubmit = async () => {
    if (!currentUser) {
      // 코드 리뷰 P3 — 로그인 사용자 정보가 아직 로드되지 않은 순간의 제출을 조용히 무시하면
      // 사용자는 버튼이 왜 반응하지 않는지 알 수 없다(무피드백 오동작). 안내 문구로 사유를 알린다.
      setSubmitBlockedMessage('사용자 정보를 불러오는 중입니다. 잠시 후 다시 시도해 주세요.');
      return;
    }
    setSubmitBlockedMessage(null);

    const nextErrors = validateInspectionCreateForm(values);
    setErrors(nextErrors);
    if (hasInspectionCreateFormErrors(nextErrors) || hasFileErrors) {
      return;
    }

    // 회차가 이미 생성된 뒤(업로드 실패 후 재시도)라면 이 시설물로 새로 회차를 만드는 게 아니므로
    // 중복 경고를 건너뛴다 — 안 그러면 재시도할 때마다 확인창이 다시 뜬다.
    if (!createdInspection) {
      setIsCheckingDuplicateRound(true);
      const activeRound = await findActiveRound(Number(values.facilityId));
      setIsCheckingDuplicateRound(false);
      if (activeRound !== null) {
        setDuplicateActiveRound(activeRound);
        return;
      }
    }

    await submitInspection();
  };

  const handleConfirmDuplicateCreate = () => {
    setDuplicateActiveRound(null);
    void submitInspection();
  };

  // "이어서 하기"(팀 리뷰 반영, 2026-07-28) — 새 회차를 만드는 대신 기존 진행 중 회차로 바로
  // 돌아간다. AI 분석 실행/상태 화면은 어떤 단계(대기·진행 중·완료)든 그 회차 id 하나로 알아서
  // 재구성하므로(rebuildFromDb) stage를 미리 알 필요 없이 /analysis로 보내면 된다.
  // hasSubmittedRef를 먼저 세우지 않으면 hasDraftInput이 true인 채(폼을 이미 채워둔 상태)라 바로
  // 아래 navigate가 이 페이지 자신의 useBlocker에 걸려 이탈 확인창이 또 뜨고 이동이 막힌다 —
  // submitInspection 성공 경로와 동일한 이유(PR머신 리뷰 발견, CI에서 재현).
  const handleResumeExisting = () => {
    if (duplicateActiveRound === null) return;
    hasSubmittedRef.current = true;
    clearInspectionCreateDraft();
    void clearDraftMediaFiles();
    navigate(`/inspections/${duplicateActiveRound.id}/analysis`);
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
              <label htmlFor="inspection-type" className={LABEL_CLASSES}>
                점검 유형
              </label>
              <select
                id="inspection-type"
                value={values.inspectionType}
                onChange={handleFieldChange('inspectionType')}
                className={INPUT_CLASSES}
                disabled={isFieldsLocked}
              >
                <option value="REGULAR">정기</option>
                <option value="DETAILED">정밀</option>
                <option value="EMERGENCY">긴급</option>
              </select>
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
          {submitBlockedMessage && (
            <p role="alert" className={ERROR_CLASSES}>
              {submitBlockedMessage}
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
            {showDraftSavedNotice && (
              <span role="status" className="text-xs text-text-muted">
                임시저장되었습니다
              </span>
            )}
            <Button
              type="button"
              variant="secondary"
              onClick={handleManualDraftSave}
              disabled={isFieldsLocked || !hasDraftInput}
              title={!hasDraftInput ? '임시저장할 입력 내용이 없습니다' : undefined}
            >
              임시저장
            </Button>
            <Button
              type="button"
              variant="primary"
              onClick={handleSubmit}
              disabled={isSubmitting || hasFileErrors || hasMissingRequiredInput}
              title={
                hasMissingRequiredInput && !isSubmitting
                  ? '시설물, 점검일, 업로드 파일을 모두 입력해 주세요'
                  : undefined
              }
            >
              {isSubmitting ? '처리 중...' : '업로드 완료 후 AI 분석 시작'}
            </Button>
          </div>
        </div>
      </div>

      {blocker.state === 'blocked' && !isLeaving && (
        <Modal
          open
          onClose={() => blocker.reset()}
          title="작성 중인 정보가 있습니다"
          closeOnOverlayClick={false}
        >
          <div className="flex w-80 flex-col gap-6">
            <p className="m-0 text-sm text-text-muted">
              작성을 취소하시겠습니까? (입력 내용 임시저장됨)
            </p>
            <div className="flex justify-end gap-3">
              <Button type="button" variant="secondary" onClick={() => blocker.reset()}>
                취소
              </Button>
              <Button type="button" variant="primary" onClick={handleConfirmLeave}>
                나가기
              </Button>
            </div>
          </div>
        </Modal>
      )}

      {duplicateActiveRound !== null && (
        <Modal
          open
          onClose={() => setDuplicateActiveRound(null)}
          title="이미 진행 중인 회차가 있습니다"
          closeOnOverlayClick={false}
        >
          <div className="flex w-80 flex-col gap-6">
            <p className="m-0 text-sm text-text-muted">
              이미 진행 중인 {duplicateActiveRound.roundNo}회차가 있습니다. 이어서 진행하시겠습니까,
              새 회차를 만드시겠습니까?
            </p>
            <div className="flex justify-end gap-3">
              <Button type="button" variant="secondary" onClick={() => setDuplicateActiveRound(null)}>
                취소
              </Button>
              <Button type="button" variant="secondary" onClick={handleResumeExisting}>
                이어서 하기
              </Button>
              <Button type="button" variant="primary" onClick={handleConfirmDuplicateCreate}>
                계속 생성
              </Button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  );
}
