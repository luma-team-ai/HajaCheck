## Conventions

모듈별 컨벤션 문서가 SOT — 코드 스타일 질문은 코드보다 먼저 이 문서를 확인:
- `docs/conventions/SpringBoot_코드_컨벤션.md` (backend)
- `docs/conventions/React_코드_컨벤션.md` (frontend)
- `docs/conventions/AI_개발_컨벤션.md` (ai-server, ai-dl 포함)

backend 패키지 구조(스캐폴드 완료, 대부분 `.gitkeep`만 있고 구현 없음): `auth`, `admin`, `core.{ai,defect,facility,inspection,media,report}`, `counsel`(+`counsel/websocket`) — 각 도메인 패키지 하위 `controller/dto/entity/repository/service` 5분할. `global.{common,config,exception,util}`에 공통 인프라(ApiResponse, BaseTimeEntity, BusinessException, ErrorCode, GlobalExceptionHandler)만 구현됨.

frontend 구조: `src/app`(router/main), `src/features/{landing,map,inspection}`(도메인별 pages/components/lib/api/hooks), `src/shared/api`(axios 공통 클라이언트), `src/mocks`(MSW 핸들러). 라우트 가드(인증 필요 페이지 보호) 컴포넌트는 아직 없음 — `router.tsx`에 `LandingPage`/`MapPage`/`ResultViewerPage` 전부 무가드 등록.
