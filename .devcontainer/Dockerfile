FROM mcr.microsoft.com/devcontainers/java:3.1.5-21-bookworm@sha256:02d6b8d66679f63f8e039f41eb04d70eb0ed52ba35503d0fe3e8cf531154ef8c

USER root
# The Android toolchain does not use Yarn's optional APT repository.
RUN rm -f /etc/apt/sources.list.d/yarn.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends python3 curl unzip git build-essential ninja-build \
    && rm -rf /var/lib/apt/lists/*
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}"
RUN mkdir -p /opt/android-sdk && chown vscode:vscode /opt/android-sdk
USER vscode
