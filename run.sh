#!/usr/bin/env sh
# Обёртка-супервизор агента MediaCenter (как kodi/start.sh):
# на каждой итерации проверяет git-репо, при изменениях делает pull + npm install,
# затем запускает агента. Когда агент выходит (само-обновление/краш) — цикл повторяется.
cd "$(dirname "$0")" || exit 1

CONFIG="${1:-agent-config.json}"

while true; do
  if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo main)
    echo "[$(date)] проверяю обновления ($BRANCH)…"
    git fetch origin "$BRANCH" >/dev/null 2>&1 || true
    LOCAL=$(git rev-parse HEAD 2>/dev/null)
    REMOTE=$(git rev-parse "origin/$BRANCH" 2>/dev/null)
    if [ -n "$REMOTE" ] && [ "$LOCAL" != "$REMOTE" ]; then
      echo "[$(date)] обновление $LOCAL -> $REMOTE"
      git pull --ff-only origin "$BRANCH" || git reset --hard "origin/$BRANCH"
      npm install --omit=dev --no-audit --no-fund
    fi
  fi

  echo "[$(date)] запускаю агента…"
  node index.js "$CONFIG"
  echo "[$(date)] агент вышел (код $?). Перезапуск через 3с…"
  sleep 3
done
