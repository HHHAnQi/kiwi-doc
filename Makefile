# rag-doc-platform Makefile
# 封装常用命令,简化新人上手。

SHELL := /bin/bash
COMPOSE := docker compose --env-file .env -f deploy/docker-compose.yml
GRADLE := ./gradlew

.PHONY: help env up down ps logs app test test-integration clean lint run db-migrate init-milvus eval-setup eval-gen eval-run eval-all eval-real-gen eval-ragas eval-gate eval-set-baseline badcase-run badcase-regress badcase-classify badcase-test eval-ab-retrieval

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

run: ## 启动应用(Spring Boot, 默认 dev profile, 连 docker-compose 中间件)
	@if [ -f .env ]; then \
		set -a && . ./.env && set +a && \
		echo "✓ 已加载 .env (LLM_MODEL=${LLM_MODEL:-未设})"; \
	else echo "⚠ 无 .env, 走 application.yml 默认值"; fi
	@#关键: Spring Boot 原生不读 .env(.env 是 docker-compose/make 的约定)，
	@# application-*.yml 里 ${LLM_*:default} 占位符只有在环境变量已设时才生效，
	@# 否则吃默认值(qwen-max + dashscope URL)导致 401。必须先 source .env 再跑 gradle。
	$(GRADLE) :platform-bootstrap:bootRun --args="--spring.profiles.active=dev"

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

# ===== V2-C 评测 =====

eval-setup: ## 建评测虚拟环境(.venv, 不污染全局 Python)
	python3 -m venv .venv
	@echo "✓ venv 已创建。激活后装依赖: source .venv/bin/activate && pip install -r eval/requirements.txt"

eval-gen: ## 合成评测题(默认 30 题, 用 make eval-gen N=200 扩到 200 题)
	@if [ ! -d .venv ]; then echo "⚠ 先跑 make eval-setup"; exit 1; fi
	@. .venv/bin/activate && python3 eval/gen_questions.py $(N)

eval-run: ## 跑评测, 输出报告 eval/eval_report.md
	@if [ ! -d .venv ]; then echo "⚠ 先跑 make eval-setup"; exit 1; fi
	@. .venv/bin/activate && python3 eval/eval_pipeline.py

eval-all: eval-gen eval-run ## 一键: 合成 + 评测

eval-real-gen: ## 生成 30 道真用户风格题(反查 chunk_id)
	@if [ ! -d .venv ]; then echo "⚠ 先跑 make eval-setup"; exit 1; fi
	@. .venv/bin/activate && python3 eval/gen_real_questions.py

eval-ragas: ## 跑 RAGAS 评测(faithfulness/answer_relevancy/context 指标)
	@if [ ! -d .venv ]; then echo "⚠ 先跑 make eval-setup"; exit 1; fi
	@. .venv/bin/activate && python3 eval/ragas_pipeline.py

eval-gate: ## CI 门禁: 跑 RAGAS + 对比 baseline, 任一指标降超 3% 退出非零(阻 PR)
	@if [ ! -d .venv ]; then echo "⚠ 先跑 make eval-setup"; exit 1; fi
	@. .venv/bin/activate && python3 eval/ragas_pipeline.py --gate

eval-set-baseline: ## 把本次 RAGAS 结果存为 baseline(手工批准后用)
	@if [ ! -d .venv ]; then echo "⚠ 先跑 make eval-setup"; exit 1; fi
	@. .venv/bin/activate && python3 eval/ragas_pipeline.py --set-baseline

.PHONY: eval-setup eval-gen eval-run eval-all eval-real-gen eval-ragas eval-gate eval-set-baseline eval-ab-retrieval

# ─── Task 5 / V11 Hybrid Retrieval AB 评测 ─────────────────────
eval-ab-retrieval: ## dense vs hybrid 检索 AB 评测, 输出 eval/dense_vs_hybrid_report.json
	@if [ ! -d .venv ]; then echo "⚠ 先跑 make eval-setup"; exit 1; fi
	@if ! curl -sf $(RETRIEVE_URL) >/dev/null 2>&1; then \
		echo "⚠ backend 未启动或不通: RETRIEVE_URL=$(RETRIEVE_URL), 先 make run"; exit 1; fi
	@. .venv/bin/activate && python3 eval/runner/ab_dense_vs_hybrid.py \
		--dataset eval/datasets/retrieval_eval.jsonl \
		--k 5 --output eval/dense_vs_hybrid_report.json

# ─── Badcase Management (BADCASE-001) ──────────────────────────
# 评测 venv 优先 eval/.venv (新框架), 回退 .venv (老 eval-setup)
VENV_BADCASE := $(shell [ -d eval/.venv ] && echo "eval/.venv" || ([ -d .venv ] && echo ".venv" || echo ""))

badcase-classify: ## 仅做分类 + 格式 smoke, 不调线上 (backend 可不起)
	@if [ -z "$(VENV_BADCASE)" ]; then echo "⚠ 先 python3 -m venv eval/.venv && eval/.venv/bin/pip install requests pytest"; exit 1; fi
	@$(VENV_BADCASE)/bin/python badcase/regression/runner.py --skip-remote

badcase-run: ## 跑一次回归 (调真实 /chat + /retrieve), 生成 badcase/badcase_report.json
	@if [ -z "$(VENV_BADCASE)" ]; then echo "⚠ 先 python3 -m venv eval/.venv && eval/.venv/bin/pip install requests pytest"; exit 1; fi
	@$(VENV_BADCASE)/bin/python badcase/regression/runner.py

badcase-regress: ## CI 模式: 同 badcase-run, 任一 regression → 非 0 退出
	@if [ -z "$(VENV_BADCASE)" ]; then echo "⚠ 先 python3 -m venv eval/.venv && eval/.venv/bin/pip install requests pytest"; exit 1; fi
	@$(VENV_BADCASE)/bin/python badcase/regression/runner.py --ci

badcase-test: ## 跑 badcase 模块的纯函数单测 (不依赖网络/容器)
	@if [ -z "$(VENV_BADCASE)" ]; then echo "⚠ 先 python3 -m venv eval/.venv && eval/.venv/bin/pip install pytest"; exit 1; fi
	@$(VENV_BADCASE)/bin/python -m pytest eval/tests/badcase/ -q

.PHONY: badcase-classify badcase-run badcase-regress badcase-test
