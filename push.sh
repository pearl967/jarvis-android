#!/bin/bash
# Публикация репозитория на GitHub-профиль после авторизации через gh auth login.
# В qroot gh не может делать fork/exec git, поэтому push выполняется напрямую
# через git с короткоживущим токеном из gh auth token.
set -e
cd "$(dirname "$0")"

USER=$(gh api user --jq .login)
REPO="jarvis-android"
REMOTE="https://github.com/$USER/$REPO.git"

git branch -M main
if gh repo view "$USER/$REPO" >/dev/null 2>&1; then
  git remote add origin "$REMOTE" 2>/dev/null || git remote set-url origin "$REMOTE"
else
  gh repo create "$REPO" --public >/dev/null
  git remote add origin "$REMOTE" 2>/dev/null || git remote set-url origin "$REMOTE"
fi

TOKEN=$(gh auth token)
git remote set-url origin "https://x-access-token:${TOKEN}@github.com/$USER/$REPO.git"
git push -u origin main
git remote set-url origin "$REMOTE"

# GitHub Pages из корня ветки main
gh api -X POST "repos/$USER/$REPO/pages" \
  -f 'source[branch]=main' -f 'source[path]=/' >/dev/null 2>&1 || \
gh api -X PATCH "repos/$USER/$REPO/pages" \
  -f 'source[branch]=main' -f 'source[path]=/' >/dev/null 2>&1 || true

echo "Готово: https://github.com/$USER/$REPO"
echo "Страница: https://$USER.github.io/$REPO/"
