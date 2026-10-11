PY := .venv/bin/python

.PHONY: help setup web-install web-build ui dev test test-py test-web check android-check

help:
	@echo "setup        install the Python and web interface dependencies"
	@echo "ui           start the web interface at http://127.0.0.1:8000"
	@echo "dev          backend with reload + Vite on :5173 (two terminals)"
	@echo "test         the whole suite (pytest + vitest)"
	@echo "web-build    build the SPA into web/dist"
	@echo "android-check  the Android client's checks (Gradle from android/, see android/README.md)"

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
	@echo "Terminal 2: cd web && npm run dev      (proxies /api → :8000)"

test: test-py test-web

test-py:
	$(PY) -m pytest tests/ -q

test-web:
	cd web && npm run test

check: test
	cd web && npm run typecheck

# The Android client (android/README.md): unit tests, lint and the licence check, both APKs, the didactic-content
# drift check once :core:content exists, and the repository hygiene check. The managed-emulator suites are separate
# (./gradlew pixel9Api36DebugAndroidTest, :qasmoke:pixel9Api36QaAndroidTest).
ANDROID_CONTENT_CHECK := $(if $(wildcard android/core/content/build.gradle.kts),:core:content:verifyDidacticContent)

android-check:
	cd android && ./gradlew check lintDebug assembleDebug assembleRelease $(ANDROID_CONTENT_CHECK)
	cd android && python3 scripts/hygiene/check_repo.py
