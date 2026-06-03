FROM mcr.microsoft.com/devcontainers/java:21-bookworm

# 한국어 로케일
RUN apt-get update && apt-get install -y locales \
    && sed -i 's/# ko_KR.UTF-8 UTF-8/ko_KR.UTF-8 UTF-8/' /etc/locale.gen \
    && locale-gen \
    && update-locale LANG=ko_KR.UTF-8 LC_ALL=ko_KR.UTF-8 \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

ENV LANG=ko_KR.UTF-8
ENV LC_ALL=ko_KR.UTF-8
ENV TZ=Asia/Seoul

# Maven
RUN apt-get update && apt-get install -y maven \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

# Node.js 20 (Claude Code 실행용)
RUN curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y nodejs \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

# Docker CLI (docker-outside-of-docker)
RUN curl -fsSL https://download.docker.com/linux/debian/gpg | gpg --dearmor -o /usr/share/keyrings/docker-archive-keyring.gpg \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/docker-archive-keyring.gpg] \
       https://download.docker.com/linux/debian bookworm stable" \
       > /etc/apt/sources.list.d/docker.list \
    && apt-get update && apt-get install -y docker-ce-cli docker-compose-plugin \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

# Claude Code (root로 전역 설치)
RUN npm install -g @anthropic-ai/claude-code
