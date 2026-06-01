FROM node:20-alpine
WORKDIR /app
COPY package.json .
RUN npm install --omit=dev
COPY index.js .
RUN mkdir -p /app/scenarios
EXPOSE 8082
CMD ["node", "index.js"]
