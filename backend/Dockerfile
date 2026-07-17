# syntax=docker/dockerfile:1
# HajaCheck backend (Spring Boot) — 멀티스테이지 빌드
# 버전은 build.gradle(Java 17 · Gradle 8.14.3, gradle/wrapper/gradle-wrapper.properties)을 그대로 따른다.

# ---- Build stage ----
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace

# Gradle 래퍼·빌드 스크립트 먼저 복사 → 의존성 레이어 캐시(소스만 바뀌면 이 레이어 재사용)
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew

COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

# ---- Runtime stage ----
FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app

# HEALTHCHECK용 curl만 설치(빌드 도구는 build 스테이지에만 존재 — 런타임 이미지에 미포함)
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r spring && useradd -r -g spring -d /app -s /usr/sbin/nologin spring \
    # uploads는 named volume 마운트 대상(#323) — 이미지에 경로가 미리 없으면 Docker가 빈 볼륨을
    # root:root 755로 새로 만들어 non-root spring이 쓰기 실패한다. 미리 만들어 소유권을 아래
    # chown -R로 함께 넘겨줘야 볼륨이 그 소유권을 상속받는다(ai-server/Dockerfile의 chroma_data와 동일 패턴).
    && mkdir -p /app/uploads/hajacheck-files \
    && chown -R spring:spring /app

COPY --from=build --chown=spring:spring /workspace/build/libs/*.jar app.jar

USER spring
EXPOSE 8080

# 127.0.0.1 명시 — 컨테이너 내부 "localhost"의 IPv6 우선 해석으로 인한 연결 실패 회피
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=6 \
    CMD curl -fsS http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
