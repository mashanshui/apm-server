FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# 健康检查使用 curl；运行用户不使用 root，附件目录由应用用户持有。
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home --shell /usr/sbin/nologin apm \
    && mkdir -p /var/lib/apm/memory-report-artifacts \
    && chown -R apm:apm /var/lib/apm

# 由部署前的 bootJar 任务生成；排除 Gradle 的 plain JAR。
COPY backend/build/libs/*-SNAPSHOT.jar /app/app.jar

USER apm
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
