# handoff: #652 시설물 대표 사진 업로드 프론트 실연동

> **문서 버전:** v0.1 · **최종 수정:** 2026-07-28

## 이슈/사이클
- GitHub #652 — 백엔드는 #1017/#632로 이미 완료(`POST`/`GET /api/facilities/{facilityId}/media`). 이번 스코프는 **프론트엔드만**.
- **사이클: Normal**
- 워크트리: 기존 `C:\AISOURCE\hajacheck\HajaCheck-wt-defect-detail-fields` 재사용 가능하면 재사용, 새 워크트리가 필요하면 `HajaCheck-wt-652-facility-photo-upload` + 브랜치 `feature/652-facility-photo-upload`(origin/dev 기준)로 별도 생성 — **메인이 지정한 워크트리 경로를 그대로 따를 것**.

## ⚠️ 동시 편집 주의 — `FacilityListPage.tsx`
다른 세션(Claude B, #671 — 시설물 목록 테이블→카드형 레이아웃 전환)이 **같은 파일**을 동시에 수정 중입니다. 충돌 최소화를 위해 **`handleSubmit` 함수 본문만 수정하고, JSX/렌더 트리(`return (...)` 이하, `FacilityTable`/카드 레이아웃 관련 부분)는 절대 건드리지 말 것**. 완료 순서에 따라 먼저 push하는 쪽 기준으로 나중 쪽이 rebase하기로 합의됨 — 이 브랜치가 나중에 rebase해야 할 수 있음을 인지할 것.

## 백엔드 계약 (이미 구현됨, 참고만)
- `POST /api/facilities/{facilityId}/media` — multipart, 필드명 `files`(inspection media 업로드와 동일 컨벤션), 응답 `ApiResponse<List<MediaResponse>>`, HTTP 201
- `GET /api/facilities/{facilityId}/media` — 응답 `ApiResponse<List<MediaResponse>>`, HTTP 200
- `MediaResponse`: `{id, inspectionId, fileType, thumbnailUrl, detailUrl, mimeType, capturedAt, gpsLat, gpsLng, createdAt}` — facility 사진은 `inspectionId`가 null. **응답에 `facilityId` 필드 없음**(어느 시설물 것인지는 호출한 엔드포인트로만 구분).
- 4장 제한은 **누적 기준**(기존 보유 + 이번 업로드 합이 4 초과 시 전체 배치 거부, `ErrorCode.FACILITY_PHOTO_COUNT_EXCEEDED`, HTTP 400) — 신규 등록 폼(항상 보유 0장에서 시작)에서는 사실상 "이번에 고르는 파일 4장 초과"만 신경 쓰면 됨.
- 인가: `facilityService.get(userId, companyId, facilityId)` 경유 — 회사 스코프 밖 요청은 `FACILITY_NOT_FOUND`류로 처리됨(IDOR 방지, 프론트에서 별도 처리 불필요).

## 기존 프론트 상태 (전부 확인됨)
- `FacilityPhotoUploadField.tsx`(`frontend/src/features/facility/components/`) — **이미 UI 완성**(#629), 로컬 `useState<StagedPhoto[]>`로 미리보기/드래그드롭/개별삭제까지 구현됨. **`onFilesChange` prop이 아직 없어 선택 파일이 컴포넌트 밖으로 안 나감**(컴포넌트 자체 주석에 "#652에서 확장" 예고돼 있음) — 이번에 추가.
- `FacilityFormModal.tsx` — `<FacilityPhotoUploadField />` 호출 지점(line 256) 존재, `onSubmit: (payload: CreateFacilityRequest) => Promise<void>` 시그니처. 사진 미전송.
- `FacilityListPage.tsx` `handleSubmit`(line 51-57): `await createFacility(payload); handleCloseModal();` — `createFacility`는 `useCreateFacility().createFacility`(= `mutation.mutateAsync`)라 **생성된 `Facility`(id 포함)를 반환함** — 사진은 시설물 생성 후에만 업로드 가능하므로 이 반환값을 활용.
- 참고 구현 정확히 있음: `frontend/src/features/inspection/api/mediaApi.ts` + `useUploadMedia.ts` + `mediaApi.handlers.ts` — 멀티파일 업로드(필드명 `files`, progress 콜백, MSW 패턴) 그대로 미러링하면 됨.
- `facilityApi.ts`에는 사진 관련 함수 없음 — 이 레포 컨벤션(관심사별 API 파일 분리, `facilityDefectApi.ts`/`facilityComparisonApi.ts` 선례)대로 **신규 `facilityMediaApi.ts`**로 분리.
- `types.ts`에 사진 타입 없음 — feature 간 직접 import 금지 컨벤션이라 `inspection/types.ts`의 `Media`를 import하지 말고 **로컬로 복제**(`FacilityPhoto` 등 이름으로).

## 구현 범위

### 1. `frontend/src/features/facility/types.ts`
- `FacilityPhoto` 타입 추가(로컬 복제, `inspection.Media`와 동일 필드 셋에서 facility 맥락에 안 쓰는 `inspectionId` 등은 유지하되 무시 가능)
- `types.ts:24-25`의 stale 주석("대표 사진은 별도 테이블이라 백엔드 계약에 아직 없다") 정리

### 2. `frontend/src/features/facility/api/facilityMediaApi.ts` (신규) + `.handlers.ts` (신규)
- `inspection/api/mediaApi.ts` 패턴 그대로: `upload(facilityId, files, onUploadProgress?)` → `POST /facilities/{facilityId}/media`(필드명 `files`), `list(facilityId)` → `GET /facilities/{facilityId}/media`
- MSW 핸들러: `inspection/api/mediaApi.handlers.ts` 패턴 그대로(모듈 스코프 스토어, `FILE_REQUIRED`/`FACILITY_PHOTO_COUNT_EXCEEDED` 재현), `frontend/src/mocks/handlers.ts`에 등록

### 3. `frontend/src/features/facility/hooks/useUploadFacilityPhotos.ts` (신규)
- `inspection/hooks/useUploadMedia.ts` 패턴 그대로(TanStack `useMutation`, progress state)

### 4. `FacilityPhotoUploadField.tsx` 수정
- `onFilesChange?: (files: File[]) => void` prop 추가, 기존 add/remove 핸들러에서 파일 배열이 바뀔 때마다 호출. **기존 미리보기/드래그드롭/objectURL cleanup 로직은 그대로 유지**(재구현 금지, prop만 얹기).

### 5. `FacilityFormModal.tsx` 수정
- `stagedPhotoFiles` 로컬 state 추가, `<FacilityPhotoUploadField onFilesChange={setStagedPhotoFiles} />`
- `Props.onSubmit` 시그니처를 `(payload: CreateFacilityRequest, photos: File[]) => Promise<void>`로 변경
- `handleSubmit` 내부에서 `await onSubmit(payload, stagedPhotoFiles)`로 변경
- 성공 시 `stagedPhotoFiles`도 `setValues(FACILITY_FORM_INITIAL_VALUES)`와 함께 초기화
- 하단 안내 문구("사진 업로드 연동은 준비 중입니다(#652)...") 제거/갱신

### 6. `FacilityListPage.tsx` 수정 — **`handleSubmit` 함수 본문만, JSX 건드리지 말 것**
```ts
const { uploadPhotos } = useUploadFacilityPhotos(); // 신규 훅
const handleSubmit = async (payload: CreateFacilityRequest, photos: File[]) => {
  const facility = await createFacility(payload);
  if (photos.length > 0) {
    await uploadPhotos(facility.id, photos);
  }
  handleCloseModal();
};
```
- **알려진 제한사항(문서화만, 이번 스코프에서 수정 안 해도 됨)**: 시설물 생성은 성공했는데 사진 업로드가 실패하면, `FacilityFormModal`의 try/catch가 이를 잡아 폼을 유지하지만 시설물은 이미 생성된 상태 — 사용자가 다시 제출하면 중복 시설물이 생길 수 있음. 이번 PR에서는 이 엣지케이스를 자동 처리하지 않고, 커밋 메시지/PR 본문에 알려진 제한사항으로만 명시(재시도 유도 대신 "사진 업로드만 실패했습니다, 시설물 상세에서 다시 시도하세요" 식 메시지 구분은 후속 이슈로 남겨도 됨 — 서브에이전트 판단에 맡김).

## 테스트
- `FacilityPhotoUploadField.test.tsx`: `onFilesChange` 호출 검증 추가(기존 테스트 유지)
- `FacilityFormModal.test.tsx`(있다면): 새 `onSubmit` 시그니처 반영
- `FacilityListPage.test.tsx`: 시설물 생성 성공 후 사진 있으면 업로드 API가 호출되는지, 없으면 호출 안 되는지
- 신규: `facilityMediaApi.test.ts`(또는 유사) — api 함수 자체 테스트
- MSW 목 기반 통합 시나리오 최소 1건(등록+사진 업로드 전체 플로우)

## 완료 기준
- `npx tsc --noEmit` 클린
- `npx vitest run src/features/facility` 전부 PASS
- `FacilityListPage.tsx`의 diff가 `handleSubmit` 본문 + import 추가로만 국한되는지 최종 확인(다른 세션과 충돌 최소화)

## 책임 (이것만)
1. 워크트리+브랜치(메인 지정 경로 사용)
2. 코드 + 테스트
3. 빌드/테스트 PASS, 오류 0
4. 단계별 커밋(feat: 타입+API+훅 → fix: PhotoUploadField onFilesChange → feat: 모달+리스트페이지 연동 → test: 테스트), **push 금지**
5. 메인에 보고: 변경파일 절대경로 + 의도 + 빌드결과, 특히 `FacilityListPage.tsx` diff가 handleSubmit에 국한됐는지 명시

## 금지 (메인이 함)
self-review·security-review 호출 / PR 작성·생성 / push / 머지 / STATUS.md 수정 / rebase(다른 세션과의 순서 조율은 메인이 함).
