# syntax=docker/dockerfile:1.7
# =====================================================================
# MindCrew 后端镜像 · Spring Boot 3.3 / Java 17
# 多阶段构建：build 阶段用 Maven 打包，runtime 阶段只带 JRE + 必备依赖
# =====================================================================

# ─────────────────────────────────────────────────────────────
# 1) 构建阶段：拉依赖 + 打 fat jar
# ─────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# 先单独 COPY pom.xml，利用 Docker 层缓存：源码改动不会触发依赖重下
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q dependency:go-offline -B

# 再 COPY 源码并打包
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q clean package -Dmaven.test.skip=true -B && \
    cp target/MindCrew-*.jar /build/app.jar

# ─────────────────────────────────────────────────────────────
# 2) 运行阶段：JRE 17 + LibreOffice + FFmpeg
#   LibreOffice → 老格式 Office (.doc/.xls/.ppt) 转 OOXML
#   FFmpeg      → 视频抽音轨/关键帧
#   字体        → LibreOffice 渲染中文 PPT/Word
# ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy AS runtime

ENV DEBIAN_FRONTEND=noninteractive \
    TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

RUN apt-get update && apt-get install -y --no-install-recommends \
        libreoffice \
        libreoffice-l10n-zh-cn \
        fonts-noto-cjk \
        fonts-wqy-zenhei \
        ffmpeg \
        curl \
        tzdata \
    && ln -fs /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo $TZ > /etc/timezone \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# 用非 root 用户跑，避免容器内任意写
RUN groupadd -r app && useradd -r -g app -m -d /home/app app \
    && mkdir -p /app/uploads /app/logs \
    && chown -R app:app /app

COPY --from=builder --chown=app:app /build/app.jar /app/app.jar

USER app

EXPOSE 8080

# 上传卷：默认挂到 /app/uploads，application.yml 里 upload.path=uploads 是相对路径
VOLUME ["/app/uploads"]

# 健康检查 · Spring Boot Actuator 没装，用最简单的 TCP 端口探测
HEALTHCHECK --interval=20s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fsS http://localhost:8080/ -o /dev/null || exit 1

# 通过 JAVA_OPTS 注入 JVM 参数
ENV JAVA_OPTS="-Xms512m -Xmx2048m -XX:+UseG1GC"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
