FROM node:20-alpine
WORKDIR /app
COPY package.json .
RUN npm install --omit=dev
COPY index.js .
# scenarios 볼륨 마운트 포인트
RUN mkdir -p /app/scenarios
EXPOSE 8081
CMD ["node", "index.js"]
