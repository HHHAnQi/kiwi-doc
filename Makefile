# rag-doc-platform Makefile
# 封装常用命令,简化新人上手。

SHELL := /bin/bash
COMPOSE := docker compose --env-file .env -f deploy/docker-compose.yml
GRADLE := ./gradlew

.PHONY: help env up down ps logs app test test-integration clean lint run db-migrate init-milvus

help: ## 显示所有命令
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}'

env: ## 从 .env.example 复制 .env
	@if [ ! -f .env ]; then cp .env.example .env && echo "✓ 已创建 .env"; else echo "⚠ .env 已存在,跳过"; fi

up: ## 启动所有中间件(MySQL/Redis/MinIO/Milvus)
	$(COMPOSE) up -d
	@echo "✓ 中间件已启动。MinIO 控制台: http://localhost:9001 (minio/minio123)"

down: ## 停止所有中间件
	$(COMPOSE) down

ps: ## 查看容器状态
	$(COMPOSE) ps

logs: ## 跟踪中间件日志
	$(COMPOSE) logs -f --tail=100

app: ## 构建应用 jar
	$(GRADLE) :platform-bootstrap:bootJar

run: ## 启动应用(Spring Boot)
	$(GRADLE) :platform-bootstrap:bootRun

test: ## 单元测试
	$(GRADLE) test

test-integration: ## 集成测试(Testcontainers 自动起容器)
	$(GRADLE) test

lint: ## Spotless 格式检查
	$(GRADLE) spotlessCheck

clean: ## 清理构建产物
	$(GRADLE) clean

db-migrate: ## 手动触发 Flyway 迁移(应用启动时自动)
	@echo "Flyway 在 app 启动时自动执行,无需手动"

init-milvus: ## 初始化 Milvus collection
	python3 scripts/init-milvus.py || echo "⚠ 需要 python3 + pymilvus,详见 scripts/README"
