# syntax=docker/dockerfile:1.7
# =====================================================================
# MindCrew 前端镜像 · Vue 3 + Vite → 静态资源由 Nginx 托管
# =====================================================================

# ─────────────── 构建阶段 ───────────────
FROM node:20-alpine AS builder

WORKDIR /app

# 利用层缓存：先装依赖，源码改动不重新装
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund --prefer-offline

COPY . .
# 只跑 vite 打包，跳过 vue-tsc 类型检查（type-check 失败不应阻断镜像构建）
RUN npm run build-only

# ─────────────── Nginx 运行阶段 ───────────────
FROM nginx:1.27-alpine AS runtime

# 删默认 default.conf，换成项目自带的
RUN rm /etc/nginx/conf.d/default.conf

COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/mindcrew.conf

EXPOSE 80

HEALTHCHECK --interval=20s --timeout=3s --retries=3 \
    CMD wget -qO- http://localhost/ >/dev/null 2>&1 || exit 1

CMD ["nginx", "-g", "daemon off;"]
