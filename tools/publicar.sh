#!/bin/bash
# Publica no repo PÚBLICO uma cópia do branch "privado" SEM o LocalConfig.kt.
# Uso: tools/publicar.sh "mensagem do commit"
set -e
msg="${1:?uso: tools/publicar.sh \"mensagem\"}"
top=$(git rev-parse --show-toplevel); pub="$top/../.pp5rd-status-app-pub"
[ -d "$pub" ] || git -C "$top" worktree add -q "$pub" publico
cd "$pub"
git rm -rq --cached . >/dev/null 2>&1 || true
git checkout -q privado -- .
git rm -q --cached -f app/src/main/java/com/pp5rd/status/LocalConfig.kt
rm -f app/src/main/java/com/pp5rd/status/LocalConfig.kt
git add -A
if git diff --cached --quiet; then echo "nada novo para publicar"; exit 0; fi
git commit -qm "$msg"
git push origin publico:main   # a trava pre-push confere antes de enviar
echo "publicado: $(git log --oneline -1)"
