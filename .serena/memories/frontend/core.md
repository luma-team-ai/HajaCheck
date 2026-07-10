## frontend (React/Vite) — 세부

- 라우터: `src/app/router.tsx` — `LandingPage`(`/`), `MapPage`(`/map`), `ResultViewerPage` 등록. **인증 가드 없음**(`mem:progress` 참고) — 인증 필요 라우트에 ProtectedRoute 패턴 추가 시 이 파일에 래핑 필요.
- 지도(`features/map`): 카카오맵 SDK 로더(`lib/loadKakaoMapSdk.ts`, 재시도 로직 포함)·시설물 마커 생성(`lib/createFacilityMarker.ts`, (0,0) 좌표는 GPS 결측 센티널로 무효 처리·InfoWindow 인스턴스 재사용) — 관련 PR머신 리뷰 반영 이력 있음(c6a580a, ff60376).
- 결과 뷰어(`features/inspection`): MSW 목업(`inspectionApi.handlers.ts`, `mocks/inspectionResult.mock.ts`)으로 백엔드 없이 개발 가능한 구조 — 실 API 연동 시 `inspectionApi.ts` 교체.
- 코드 컨벤션 SOT: `docs/conventions/React_코드_컨벤션.md`.
