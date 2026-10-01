#!/bin/sh
# Post-deploy check:  ./scripts/smoke-test.sh https://your-site.vercel.app
# Confirms the website, the proxied API, the sign-in options and the security headers. Exit code 1 on any failure.
set -u
SITE="${1:?usage: smoke-test.sh https://your-site.vercel.app}"
SITE="${SITE%/}"
fail=0
check() { # description, command that succeeds when the check passes
  if sh -c "$2" >/dev/null 2>&1; then echo "ok    $1"; else echo "FAIL  $1"; fail=1; fi
}
echo "Checking $SITE (a sleeping free server can take a few minutes; retrying the API for up to 5 minutes)"
check "website serves the app" "curl -fsS '$SITE/' | grep -qi '<div id=\"root\">'"
check "deep links fall back to the app" "curl -fsS '$SITE/privacy' | grep -qi '<div id=\"root\">'"
OPTIONS=""
for i in $(seq 1 30); do
  OPTIONS=$(curl -fsS --max-time 20 "$SITE/api/auth/options" 2>/dev/null) && break
  OPTIONS=""; sleep 10
done
check "API reachable through the website (/api/auth/options)" "[ -n '$OPTIONS' ]"
check "Google client id is configured" "echo '$OPTIONS' | grep -q 'apps.googleusercontent.com'"
check "password sign-in is off" "echo '$OPTIONS' | grep -q '\"passwordLogin\":false'"
check "demo login is refused" "[ \"\$(curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' -d '{\"email\":\"admin@creditsense.demo\",\"password\":\"Demo@1234\"}' '$SITE/api/auth/login')\" = 403 ]"
check "unauthenticated API call is refused" "[ \"\$(curl -s -o /dev/null -w '%{http_code}' '$SITE/api/applications')\" = 401 ]"
HEADERS=$(curl -sI "$SITE/")
check "CSP allows Google sign-in" "echo '$HEADERS' | grep -i content-security-policy | grep -q accounts.google.com"
check "clickjacking protection" "echo '$HEADERS' | grep -iq 'frame-ancestors'"
[ $fail -eq 0 ] && echo "All checks passed." || echo "Some checks failed."
exit $fail
