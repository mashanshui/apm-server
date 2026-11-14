FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# LLVM 工具和运行库固定到 apt.llvm.org 的同一发行修订，按架构校验包摘要。
ARG TARGETARCH
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl libedit2 libffi8 libxml2 libzstd1 libcurl4 libpfm4 libstdc++6 util-linux \
    && case "$TARGETARCH" in \
       arm64) tool_sha=87d9f28842bbbde18824cdac8e63c401b90af0ed9d8cd2d9bb9bac58b5e9dbf8; lib_sha=51ced6b5e9ca9c2cd0762232ed8c358cf9091381d41e107d4bbf90e16e8db9ac ;; \
       amd64) tool_sha=d21e3078314ffaee5c500f9ae19bf18a2ae0bc69b239880504b0852dc8d321e6; lib_sha=558fbf3cf29c1d3d742e47901bda6079128ae2202a6aa3957126d6e674d23541 ;; \
       *) echo '不支持的 LLVM 镜像架构' >&2; exit 1 ;; esac \
    && version='19.1.7~++20250114103320+cd708029e0b2-1~exp1~20250114103432.75' \
    && base='https://apt.llvm.org/jammy/pool/main/l/llvm-toolchain-19' \
    && curl -fsSL "$base/llvm-19_${version}_${TARGETARCH}.deb" -o /tmp/llvm.deb \
    && curl -fsSL "$base/libllvm19_${version}_${TARGETARCH}.deb" -o /tmp/libllvm.deb \
    && echo "$tool_sha  /tmp/llvm.deb" | sha256sum -c - \
    && echo "$lib_sha  /tmp/libllvm.deb" | sha256sum -c - \
    && dpkg-deb -x /tmp/llvm.deb / \
    && dpkg-deb -x /tmp/libllvm.deb / \
    && rm /tmp/llvm.deb /tmp/libllvm.deb \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home --shell /usr/sbin/nologin apm \
    && mkdir -p /var/lib/apm/memory-report-artifacts /var/lib/apm/symbols \
    && chown -R apm:apm /var/lib/apm

# 单次工具进程继承 Linux 内存和 CPU 上限，不使用 shell 拼接地址参数。
COPY docker/native-symbolizer-launcher.sh /usr/local/bin/apm-native-symbolizer
COPY docker/native-symbolizer-sandbox.c /tmp/native-symbolizer-sandbox.c
RUN apt-get update \
    && apt-get install -y --no-install-recommends gcc libc6-dev patchelf \
    && gcc -std=c11 -O2 -Wall -Wextra -Werror -fPIC -shared /tmp/native-symbolizer-sandbox.c -o /usr/local/lib/apm-native-symbolizer-sandbox.so \
    && patchelf --add-needed /usr/local/lib/apm-native-symbolizer-sandbox.so /usr/lib/llvm-19/bin/llvm-symbolizer \
    && apt-get purge -y --auto-remove gcc libc6-dev patchelf \
    && rm -rf /var/lib/apt/lists/* /tmp/native-symbolizer-sandbox.c \
    && chmod 0755 /usr/local/bin/apm-native-symbolizer

# 由部署前的 bootJar 任务生成；排除 Gradle 的 plain JAR。
COPY backend/build/libs/*-SNAPSHOT.jar /app/app.jar

USER apm
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
