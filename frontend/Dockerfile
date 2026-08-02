# 前端 SPA 多阶段构建镜像
# build 阶段: node 20 alpine 编译 dist/
# serve 阶段: nginx alpine 托管静态资源 + 反代后端
#
# 用法(配合 deploy/docker-compose.yml):
#   docker build -t ragdoc/frontend:latest frontend/
#   docker run -p 8088:80 ragdoc/frontend:latest
#
# 与 docker-compose 编排: 见根 deploy/docker-compose.yml 的 frontend service。

# ---------- build ----------
FROM node:20-alpine AS build
WORKDIR /app

# 先拷 lockfile 利用 docker layer 缓存
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

# 再拷源码构建
COPY . .
# VITE_API_BASE 留空 = 同源相对路径, 由本镜像内的 nginx 反代后端
ARG VITE_API_BASE=""
ENV VITE_API_BASE=$VITE_API_BASE
RUN npm run build

# ---------- serve ----------
FROM nginx:alpine AS serve

# 把构建产物拷到 nginx html 根
COPY --from=build /app/dist /usr/share/nginx/html

# 用项目专属 nginx 配置覆盖默认 default.conf
COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf

# nginx 默认前台 -g "daemon off;", 直接接 SIGTERM 优雅退出
EXPOSE 80

# 健康检查(OPS-4): 容器内 curl 自探 /healthz
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -qO- http://127.0.0.1/healthz || exit 1
