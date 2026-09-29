FROM node:24.19.0-alpine AS build
WORKDIR /app
COPY mcp-server/package.json mcp-server/package-lock.json ./
RUN npm ci
COPY mcp-server/tsconfig.json ./
COPY mcp-server/src ./src
RUN npm run build

FROM node:24.19.0-alpine
WORKDIR /app
ENV NODE_ENV=production
COPY mcp-server/package.json mcp-server/package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force
COPY --from=build /app/dist/src ./dist/src
USER node
EXPOSE 3010
CMD ["node", "dist/src/index.js"]
