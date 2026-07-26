PY := .venv/bin/python

.PHONY: help setup web-install web-build ui dev test test-py test-web check

help:
	@echo "setup        instala dependencias de Python y de la interfaz"
	@echo "ui           levanta la interfaz web en http://127.0.0.1:8000"
	@echo "dev          backend con recarga + Vite en :5173 (dos terminales)"
	@echo "test         toda la batería (pytest + vitest)"
	@echo "web-build    compila la SPA en web/dist"

setup:
	uv pip install --python $(PY) -r requirements.txt -e .
	cd web && npm install

web-install:
	cd web && npm install

web-build:
	cd web && npm run build

ui: web-build
	$(PY) -m phonotrainer.cli ui

dev:
	@echo "Terminal 1: $(PY) -m phonotrainer.cli ui --reload --no-open"
	@echo "Terminal 2: cd web && npm run dev      (proxy /api → :8000)"

test: test-py test-web

test-py:
	$(PY) -m pytest tests/ -q

test-web:
	cd web && npm run test

check: test
	cd web && npm run typecheck
