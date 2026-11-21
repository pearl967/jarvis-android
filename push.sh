#!/bin/bash
# Публикация репозитория на GitHub-профиль после авторизации через gh auth login.
set -e
cd "$(dirname "$0")"

USER=$(gh api user --jq .login)
REPO="jarvis-android"

git branch -M main
if gh repo view "$USER/$REPO" >/dev/null 2>&1; then
  git remote add origin "https://github.com/$USER/$REPO.git" 2>/dev/null || \
    git remote set-url origin "https://github.com/$USER/$REPO.git"
else
  gh repo create "$REPO" --public --source=. --remote=origin --push
fi
git push -u origin main

# GitHub Pages из корня ветки main
gh api -X POST "repos/$USER/$REPO/pages" \
  -f 'source[branch]=main' -f 'source[path]=/' >/dev/null 2>&1 || \
gh api -X PATCH "repos/$USER/$REPO/pages" \
  -f 'source[branch]=main' -f 'source[path]=/' >/dev/null 2>&1 || true

echo "Готово: https://github.com/$USER/$REPO"
echo "Страница: https://$USER.github.io/$REPO/"
