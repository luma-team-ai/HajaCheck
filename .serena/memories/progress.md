## 진행 상황 스냅샷 (기준: 2026-07-10, git log 665aade / docs/STATUS.md)

**주의**: 이 메모는 스냅샷이라 빠르게 stale됨 — 최신 상태는 `git log`/`docs/STATUS.md`로 재확인할 것.

- 인프라: GitHub 레포·도메인/HTTPS·개발 DB(PostgreSQL)·Redis·OAuth 앱(Kakao/Google, Testing 상태)·CI(GitHub Actions) 구축 완료. CD(서버 배포 자동화)는 미착수.
- **backend**: 패키지 스캐폴드만 완료(`auth`/`admin`/`core.*`/`counsel` 전부 `.gitkeep`), 실제 도메인 구현(엔티티/컨트롤러/서비스) 아직 없음. 공통 인프라(ApiResponse/ErrorCode/GlobalExceptionHandler)만 구현.
- **ai-server**: `/health`, `/ai/ping`, `/ai/defect-explain`(HAJA-20, PR #88) 구현 완료. RAG 챗봇(FR-6)·상담(FR-7) 등 나머지 엔드포인트 미구현.
- **frontend**: 랜딩 페이지(PR #92), 지도뷰(PR #85, 시설물 마커/카카오맵 SDK), 결과 뷰어(PR #86, 하자 오버레이) 구현 완료. **인증 라우트 가드(ProtectedRoute) 없음** — `/map` 등 인증 필요 페이지가 무가드 상태로 남아있음(별도 후속 이슈 필요, 아직 이슈/PR로 안 잡힘).
- **ai-dl**: MLflow 트래킹 헬퍼(PR #94) + 학습 스크립트 예시 존재, 모델 학습 파이프라인 자체는 스캐폴드 단계.
- 전체 계약 대비 구현률: `docs/contract.md` P0 핵심 엔드포인트 11개 중 `/ai/defect-explain` 1개만 ✅. 나머지(로그인/시설물/점검/미디어/분석/보고서 등)는 담당자 배정만 있고 미구현.
- API 계약: `docs/contract.md`가 아직 ai-server 파트만 채워짐 — Spring Boot 쪽 엔드포인트는 담당자가 이어서 추가해야 함(문서상 명시된 TODO).
