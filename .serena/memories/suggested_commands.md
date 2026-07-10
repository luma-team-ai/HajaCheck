## Commands (Windows/PowerShell 환경)

- **backend**: `./gradlew compileJava` / `./gradlew test` (전역 CLAUDE.md 워크트리 워크플로우가 요구하는 표준 빌드/테스트 명령)
- **ai-server**: `pytest` (tests/ 디렉토리, test_health/test_chunking/test_defect_explain 존재)
- **frontend**: `npm run build`(tsc -b && vite build) · `npm test`(vitest run) · `npm run lint`(eslint) · `npm run dev`(vite)
- **ai-dl**: 별도 테스트 스위트 없음 — 학습/트래킹 스크립트(`example_train.py`, `tracking.py`) 직접 실행 방식.

Windows: 이 세션 셸은 Git Bash(POSIX) 또는 PowerShell 병용 — `ls`/`grep`은 Bash 도구, PowerShell에서는 `Get-ChildItem`/`Select-String` 상당.
