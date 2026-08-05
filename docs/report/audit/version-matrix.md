# 버전 정합 — 착수보고서 정정 기준 (Docker 실측)

> **문서 버전:** v0.1 · **최종 수정:** 2026-07-15 · 이전 버전 `archive/`

> 작성 2026-07-13. **출처 = 레포 실측**: `backend/build.gradle`, `ai-server/Dockerfile`·`requirements.txt`, `frontend/package.json`·`Dockerfile`, `docker-compose.yml`.
> 착수보고서 PDF의 `3.1.1 기술 스택`·`3.1.2 개발 환경` 버전을 **현재 Docker/빌드 핀 값**으로 정정하기 위한 기준표.
> 미설치 항목(YOLO·실험추적·Tailwind 등)은 온보딩(7/15) 전 미도입 상태이므로 "미설치(예정)"로 표기.

## 3.1.1 기술 스택 — Docker 실측 정정값

| 분류 | 항목 | 버전(실측) | 비고 |
|---|---|---|---|
| Frontend | React | 18.3.1 | `package.json` ^18.3.1 |
| Frontend | Vite | 6.0.3 | `package.json` ^6.0.3 |
| Frontend | react-router-dom | 6.28.0 | ^6.28.0 |
| Frontend | ~~Tailwind CSS~~ | **미설치** | package.json에 없음. 실제: zustand 5·@tanstack/react-query 5·@stomp/stompjs 7·axios 1.7·TypeScript 5.7 |
| Backend | Java | 17 | build.gradle toolchain 17 (OpenJDK 17) |
| Backend | Spring Boot | 3.3.5 | build.gradle `org.springframework.boot 3.3.5` |
| Backend | FastAPI | 0.115.6 | requirements.txt |
| Backend | uvicorn[standard] | 0.34.0 | requirements.txt |
| Backend | Node.js | 22 | frontend Dockerfile `node:22-alpine` (빌드 툴체인) |
| AI/DL | langchain | 0.3.13 | requirements.txt |
| AI/DL | langchain-huggingface | 0.1.2 | requirements.txt |
| AI/DL | langchain-ollama | 0.2.2 | requirements.txt (대체 provider) |
| AI/DL | sentence-transformers | 3.3.1 | requirements.txt |
| AI/DL | langsmith | 0.2.11 | requirements.txt (langchain 0.3.13 종속 고정) |
| AI/DL | chromadb | 0.5.23 | requirements.txt (임베디드) |
| AI/DL | torch (PyTorch) | 2.x (전이) | requirements 미핀, sentence-transformers 전이 의존(실측 2.13.0). **문서의 "1.22.0"은 실존X → 삭제/정정** |
| AI/DL | onnxruntime | 1.27.0 (전이) | requirements 미핀. onnx 패키지 자체는 미설치 |
| AI/DL | ~~MLflow~~ | **미설치(예정)** | requirements/venv에 없음 |
| AI/DL | ~~ultralytics(YOLO)~~ | **미설치(예정)** | requirements에 없음(Dockerfile 주석 명시). 문서 "8.4.90"은 실존X |
| Database | PostgreSQL | 16 | `docker-compose.yml image: postgres:16` (실측 16.13) |
| Cache/Session | Redis | 7 | `docker-compose.yml image: redis:7` (실측 7.4.9). redis-py 5.2.1 |
| Infra | Nginx | 1.27-alpine | frontend Dockerfile runtime (실측 1.27.5) |
| Infra | Docker Compose | 5개 서비스 | nginx·spring·fastapi·postgres·redis (Chroma는 임베디드, 별도 서비스 아님) |
| Utility | Pydantic | 2.10.4 | requirements.txt |
| Build | Gradle / npm | Gradle 8.14.3 | wrapper |

## 3.1.2 개발 환경 — 정정 포인트

| 항목 | 문서(오기) | 실측 정정값 |
|---|---|---|
| Python | 3.10 | **3.11** (`python:3.11-slim`, STATUS.md도 3.11) |
| Java | 17 | 17 (일치) |

## 부록 — 문서표기 vs Docker실측 vs 2026-07 최신 (감사 추적)

`❌실존X` = 그 버전번호가 릴리스된 적 없음 / `구핀` = 실존하지만 레포는 구버전 핀 / `문서<레포` = 문서가 레포보다 낮음.

| 항목 | 문서표기 | Docker실측 | 최신(’26-07) | 판정 |
|---|---|---|---|---|
| Spring Boot | 3.5.13 | 3.3.5 | 3.5.16 / 4.1.0 | 실존O·구핀 |
| Python | 3.10 | 3.11 | — | 문서 오기 |
| FastAPI | 0.110.0 | 0.115.6 | 0.139.0 | 문서<레포 |
| uvicorn | 0.51.0 | 0.34.0 | 0.51.0 | 실존O·구핀 |
| pydantic | 2.13.4 | 2.10.4 | 2.13.4 | 실존O·구핀 |
| langchain | 1.3.4 | 0.3.13 | 1.3.13 | **❌실존X**(+라인차) |
| langchain-huggingface | 1.2.2 | 0.1.2 | 1.2.2 | 실존O·구핀(라인차) |
| sentence-transformers | 5.6.0 | 3.3.1 | 5.6.0 | 실존O·구핀 |
| langsmith | 0.9.7 | 0.2.11 | 0.10.2 | **❌실존X** |
| chromadb | 1.5.9 | 0.5.23 | 1.5.9 | 실존O·구핀 |
| PyTorch(torch) | 1.22.0 | 2.x(전이) | 2.13.0 | **❌실존X** |
| onnx | 1.22.0 | 미설치 | 1.22.0 | 레포 미설치 |
| onnxruntime | 1.22.0 | 1.27.0(전이) | 1.27.0 | 실존O·구버전 |
| MLflow | 3.14.0 | 미설치 | 3.14.0 | 레포 미설치 |
| ultralytics | 8.4.90 | 미설치 | 8.4.93 | **❌실존X**·미설치 |
| PostgreSQL | 17 | 16 | 18(17존재) | 실존O·구핀 |
| Redis | 8.8.0 | 7 | 8.8.0 | 실존O·구핀 |
| Nginx | 1.31.2 | 1.27 | 1.31.2 | 실존O·구핀 |
| Node.js | 24.14.0 | 22 | 24.18.0 | 실존O·구핀 |
| React | 18 | 18.3.1 | 19.2.7 | 일치(둘 다 18) |
| Tailwind | 표기됨 | 미설치 | 4.3.2 | 레포 미설치 |

### 반드시 정정할 4건 (실존하지 않는 버전)
1. **PyTorch 1.22.0** → 존재한 적 없음(torch는 1.13 다음 2.0). 실측 2.x 또는 "(전이 의존)"
2. **langchain 1.3.4** → 실존X. 레포 실측 **0.3.13**
3. **langsmith 0.9.7** → 실존X. 레포 실측 **0.2.11**
4. **ultralytics 8.4.90** → 실존X(8.4.93). 레포 **미설치(예정)**

### 원칙
STATUS.md `## 버전 정합`이 `PRD↔레포↔OCI 실측 정합`을 관리하므로, 착수보고서 버전표는 **위 Docker 실측값으로 통일**한다. "목표 최신 버전"을 병기하려면 그 취지를 명시(현행처럼 손으로 임의 기입 금지).
