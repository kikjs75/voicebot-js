FROM node:20-alpine
WORKDIR /app
COPY package.json .
RUN npm install --omit=dev
COPY index.js .
COPY scenarios /app/scenarios
EXPOSE 8081
CMD ["node", "index.js"]
