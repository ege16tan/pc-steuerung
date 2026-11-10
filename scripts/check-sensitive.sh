#!/usr/bin/env sh
# Prüft das Repository auf sensible Daten, Zugangsdaten und Build-Artefakte.
# Wird vom Pre-Push-Hook und von GitHub Actions aufgerufen.
set -u

errors=0

fail() {
  echo "  ✗ $1"
  errors=$((errors + 1))
}

tmp_files=$(mktemp)
tmp_hits=$(mktemp)
trap 'rm -f "$tmp_files" "$tmp_hits"' EXIT

echo "🔍 Repository-Sicherheitscheck ..."

# 1) Verbotene Dateien/Pfade
git ls-files > "$tmp_files"
while IFS= read -r file; do
  [ -z "$file" ] && continue
  if printf '%s\n' "$file" | grep -Eq '(^|/)(credentials\.json|token\.json|device\.json|local\.properties|\.env|opencode\.json)$|(^|/)Android App/\.codex/|(^|/)(\.gradle|build|\.idea|\.kotlin)/|\.(jks|keystore|p12|pem|key|apk|aab)$'; then
    fail "Nicht erlaubte Datei ist getrackt: $file"
  fi
done < "$tmp_files"

# 2) Große Dateien (> 10 MB)
while IFS= read -r file; do
  [ -z "$file" ] && continue
  [ -f "$file" ] || continue
  size=$(wc -c < "$file" 2>/dev/null || echo 0)
  if [ "$size" -gt 10485760 ]; then
    fail "Datei ist größer als 10 MB: $file"
  fi
done < "$tmp_files"

# 3) Offensichtliche Secrets
secret_re='(gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16}|-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----|PC_AGENT_TOKEN[[:space:]]*=[[:space:]]*[^[:space:]]{16,})'
git grep -I -n -E "$secret_re" -- . > "$tmp_hits" 2>/dev/null || true
while IFS= read -r hit; do
  [ -z "$hit" ] && continue
  fail "Möglicher Secret gefunden: $hit"
done < "$tmp_hits"

# 4) Ergebnis
echo ""
if [ "$errors" -gt 0 ]; then
  echo "❌ Push blockiert: $errors Problem(e) gefunden."
  echo "   Entferne die Dateien/Secrets oder umgehe die Prüfung mit: git push --no-verify"
  exit 1
fi

echo "✅ Keine Probleme gefunden."
exit 0
