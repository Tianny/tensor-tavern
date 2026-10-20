# Hify 常用命令（开发 / 构建 / 打包）
# 用法：make <target>，如 make start、make package

VERSION := $(shell sed -n 's/.*<version>\([^<]*\)<\/version>.*/\1/p' pom.xml | head -n 1)
DIST_DIR := dist
PACKAGE_DIR := $(DIST_DIR)/hify-$(VERSION)

.DEFAULT_GOAL := help
.PHONY: help start stop restart build clean package

help: ## 显示帮助
	@echo "可用 target："
	@echo "  make start     启动（检查 MySQL/Redis → 构建并启动后端 → 启动前端）"
	@echo "  make stop      停止前后端（按 PID 文件，SIGTERM，超时 SIGKILL）"
	@echo "  make restart   重启（stop + start）"
	@echo "  make build     构建后端 jar + 前端 dist"
	@echo "  make clean     清理构建产物"
	@echo "  make package   打包可分发的 tar.gz（依赖 build）"

start: ## 启动
	./start.sh

stop: ## 停止
	./stop.sh

restart: stop start ## 重启

build: ## 构建后端 + 前端
	mvn -q -pl hify-app -am -DskipTests package
	cd hify-web && npm run build

clean: ## 清理构建产物
	mvn -q clean
	rm -rf hify-web/dist hify-web/node_modules/.vite $(DIST_DIR) .run
	@lsof -nP -iTCP:8080 -sTCP:LISTEN >/dev/null 2>&1 \
		&& echo "[WARN] 服务运行中：.run/ 已被清理，make stop 将找不到 PID 文件" || true

package: build ## 打包 tar.gz
	@rm -rf $(PACKAGE_DIR)
	@mkdir -p $(PACKAGE_DIR)
	cp hify-app/target/hify-app-$(VERSION).jar $(PACKAGE_DIR)/hify-app.jar
	cp -R hify-web/dist $(PACKAGE_DIR)/web
	tar -czf $(DIST_DIR)/hify-$(VERSION).tar.gz -C $(DIST_DIR) hify-$(VERSION)
	@rm -rf $(PACKAGE_DIR)
	@echo "打包完成：$(DIST_DIR)/hify-$(VERSION).tar.gz（含后端 jar 与前端静态文件 web/）"
