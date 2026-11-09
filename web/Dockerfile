FROM node:22-bookworm-slim AS build
WORKDIR /app
COPY spa/package*.json ./
RUN npm ci
COPY spa/ .
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.28-alpine
COPY nginx/nginx.conf /etc/nginx/nginx.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
