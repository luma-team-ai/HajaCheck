## Task completion checklist (모듈별)

작업 완료로 간주하기 전 반드시:
- backend 변경 시: `./gradlew compileJava` + `./gradlew test` PASS
- ai-server 변경 시: `pytest` PASS
- frontend 변경 시: `npm run build` PASS (+ 관련 있으면 `npm test`)
- 신규 API 엔드포인트 추가/변경 시: `docs/openapi.yaml`(SOT) + `docs/contract.md`(요약) 동기화
- 비즈니스 로직/DB/트랜잭션/인증 변경 → code-reviewer/security-reviewer 리뷰 필요(전역 CLAUDE.md 사이클 매트릭스 참조, 단 이 레포는 PR머신이 Normal/Trivial 티어 대신 수행 — `mem:core` PR 흐름 참고)
