FROM node:20-alpine
WORKDIR /app
COPY package.json .
RUN npm install --omit=dev
COPY index.js ui.js ./
COPY public/ ./public/
# 두 서버를 동시에 실행하는 스크립트
RUN echo '#!/bin/sh' > /app/start.sh && \
    echo 'node index.js &' >> /app/start.sh && \
    echo 'node ui.js' >> /app/start.sh && \
    chmod +x /app/start.sh
EXPOSE 8084 8085
CMD ["/app/start.sh"]
