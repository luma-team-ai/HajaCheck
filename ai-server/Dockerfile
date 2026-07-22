# syntax=docker/dockerfile:1
# HajaCheck ai-server (FastAPI) — 멀티스테이지 빌드
# requirements.txt는 그대로 install(버전 고정, 수정 금지 — AI_개발_컨벤션.md §7).
# torch는 아래에서 CPU 전용 인덱스로 미리 설치(sentence-transformers·easyocr #605 공용).
# torchvision 등 나머지 전이 의존성(easyocr/sentence-transformers)은 requirements.txt install
# 단계에서 PyPI aarch64(manylinux) 사전빌드 휠로 설치되어 OCI Ampere A1(ARM)에서도 소스 빌드가
# 필요 없다.

# ---- Build stage ----
FROM python:3.11-slim AS build
WORKDIR /app

# 일부 전이 의존성의 네이티브 확장 컴파일 대비(런타임 이미지에는 포함하지 않음)
RUN apt-get update \
    && apt-get install -y --no-install-recommends build-essential \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt ./
RUN python -m venv /opt/venv \
    && /opt/venv/bin/pip install --no-cache-dir --upgrade pip \
    && /opt/venv/bin/pip install --no-cache-dir torch --index-url https://download.pytorch.org/whl/cpu \
    && /opt/venv/bin/pip install --no-cache-dir -r requirements.txt

# ---- Runtime stage ----
FROM python:3.11-slim AS runtime
WORKDIR /app

RUN apt-get update \
    # curl=헬스체크. libgl1·libglib2.0-0=opencv(cv2) 런타임 공유 라이브러리 — 사업자등록증
    # OCR(#552, RapidOCR→EasyOCR 교체 #605)이 cv2를 로드하며, 이게 없으면 실제 OCR 호출 시
    # `libGL.so.1` import 에러가 난다(#573). EasyOCR 교체 후에도 내부적으로 cv2를 그대로
    # 쓰므로 동일하게 필요하다.
    # (앱 import 경로는 지연 import로 cv2를 요구하지 않지만, 런타임 OCR 실행에는 필요.)
    && apt-get install -y --no-install-recommends curl libgl1 libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/* \
    # UID/GID를 999로 명시 고정(#345) — useradd -r의 자동 할당값이었던 999를 그대로 박은 것.
    # chroma_data는 named volume 마운트 대상이라 최초 마운트 시점의 UID로 초기화되므로, 베이스
    # 이미지 변경 등으로 자동 할당 UID가 바뀌면 기존 볼륨을 못 읽는다. 절대 임의 값으로 바꾸지 말 것.
    && groupadd -r -g 999 fastapi && useradd -r -u 999 -g fastapi -d /app -s /usr/sbin/nologin fastapi

COPY --from=build /opt/venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

COPY --chown=fastapi:fastapi main.py ./
COPY --chown=fastapi:fastapi deps.py ./
COPY --chown=fastapi:fastapi routers ./routers
COPY --chown=fastapi:fastapi ai ./ai

# chroma_data는 볼륨 마운트 대상 — 컨테이너 내 소유권 미리 부여
RUN mkdir -p /app/chroma_data && chown -R fastapi:fastapi /app/chroma_data

# hf_cache도 named volume 마운트 대상(HF_HOME=/app/hf_cache) — bge-m3 등 임베딩 모델 캐시(#439).
# 최초 마운트 시점 UID로 초기화되므로 chroma_data와 동일하게 fastapi(999) 소유권을 미리 부여한다.
# EasyOCR 모델(#605)도 이 볼륨 하위 `easyocr/` 서브디렉터리에 캐시한다(ai/core/ocr_client.py
# EASYOCR_MODEL_DIR) — 별도 볼륨 없이 기존 마운트를 재사용하므로 여기서 별도 mkdir 불필요
# (하위 디렉터리는 fastapi 유저가 런타임에 os.makedirs로 생성, 상위 디렉터리 소유권만 있으면 충분).
RUN mkdir -p /app/hf_cache && chown -R fastapi:fastapi /app/hf_cache

USER fastapi
EXPOSE 8000

# 127.0.0.1 명시 — 컨테이너 내부 "localhost"의 IPv6 우선 해석으로 인한 연결 실패 회피
HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=6 \
    CMD curl -fsS http://127.0.0.1:8000/health || exit 1

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
