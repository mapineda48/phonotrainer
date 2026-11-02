# The virtual environment's interpreter, the system Python of the Android tooling and the Gradle wrapper differ on
# Windows. Every recipe runs under sh and under cmd.exe (GNU make's shell on Windows when no sh.exe is on PATH):
# `cd dir && command`, no `./` outside quotes, no `VAR=x command`, and text printed with $(info), not echo.
ifeq ($(OS),Windows_NT)
PY := .venv/Scripts/python.exe
PYTHON := python
GRADLEW := "./gradlew.bat"
DEV_PY = $(subst /,\,$(PY))
DEV_WEB := cd web; npm run dev      (proxies /api -> :8000)
else
PY := .venv/bin/python
PYTHON := python3
GRADLEW := ./gradlew
DEV_PY = $(PY)
DEV_WEB := cd web && npm run dev      (proxies /api → :8000)
endif

# Python's UTF-8 mode for everything started from here: on Windows, third-party code opens its data files without an
# encoding, which would decode them with the ANSI code page (the test suite stops early without it).
export PYTHONUTF8 := 1

.PHONY: help setup web-install web-build ui dev test test-py test-web check android-check

help:
	$(info setup        install the Python and web interface dependencies)
	$(info ui           start the web interface at http://127.0.0.1:8000)
	$(info dev          backend with reload + Vite on :5173 (two terminals))
	$(info test         the whole suite (pytest + vitest))
	$(info web-build    build the SPA into web/dist)
	$(info android-check  the Android client's checks (Gradle from android/, see android/README.md))
	@:

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
	$(info Terminal 1: $(DEV_PY) -m phonotrainer.cli ui --reload --no-open)
	$(info Terminal 2: $(DEV_WEB))
	@:

test: test-py test-web

test-py:
	$(PY) -m pytest tests/ -q

test-web:
	cd web && npm run test

check: test
	cd web && npm run typecheck

# The Android client (android/README.md): unit tests, lint and the licence check, both APKs, the didactic-content
# drift check once :core:content exists, and the repository hygiene check. The managed-emulator suites are separate
# (./gradlew pixel9Api36DebugAndroidTest, :qasmoke:pixel9Api36QaAndroidTest; gradlew.bat on Windows).
ANDROID_CONTENT_CHECK := $(if $(wildcard android/core/content/build.gradle.kts),:core:content:verifyDidacticContent)

android-check:
	cd android && $(GRADLEW) check lintDebug assembleDebug assembleRelease $(ANDROID_CONTENT_CHECK)
	cd android && $(PYTHON) scripts/hygiene/check_repo.py
