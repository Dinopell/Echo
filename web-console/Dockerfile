# ============================================================
# Echo Web 控制台 — Nginx 静态资源 + API 反向代理
# ============================================================

ARG REGISTRY_PREFIX=
FROM ${REGISTRY_PREFIX}node:20-alpine AS builder
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm install
COPY . .
RUN npm run build

FROM ${REGISTRY_PREFIX}nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
    CMD wget -q -O /dev/null http://localhost/ || exit 1
