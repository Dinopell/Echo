# ============================================================
# Project Echo — Spring Boot 控制面 Dockerfile
# 构建: eclipse-temurin:17-jdk  |  运行: eclipse-temurin:17-jre
# ============================================================

ARG REGISTRY_PREFIX=
ARG JAVA_BUILD_IMAGE=eclipse-temurin:17-jdk
ARG JAVA_RUNTIME_IMAGE=eclipse-temurin:17-jre

FROM ${REGISTRY_PREFIX}${JAVA_BUILD_IMAGE} AS builder

WORKDIR /app

COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew

RUN ./gradlew dependencies --no-daemon || true

COPY src src

RUN ./gradlew bootJar -x test --no-daemon

FROM ${REGISTRY_PREFIX}${JAVA_RUNTIME_IMAGE}

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /app/build/libs/echo-control-plane-*.jar app.jar

# 使用数字 UID，避免与基础镜像已有用户冲突
RUN chown 1000:1000 app.jar

USER 1000

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=5 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
