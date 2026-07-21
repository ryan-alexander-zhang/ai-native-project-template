#!/usr/bin/env bash
# Import the checked-in SigNoz dashboards (and optionally alert rules) into any environment.
#
# The dashboard JSON under dashboards/ are the SAME objects SigNoz's UI "Import JSON" consumes;
# this script POSTs them through POST /api/v1/dashboards, so an API import here == a manual UI
# import of the same file. Idempotent: it upserts by dashboard title (PUT if a same-title
# dashboard exists, else POST), so re-running never creates duplicates.
#
# The checked-in JSON use the placeholder __APP__ for display identity (dashboard titles + tags,
# alert `service` label) and for the per-service query filters (service.name / serviceName). This
# script substitutes __APP__ with SIGNOZ_APP_NAME before import, so set it to the application's
# service name (spring.application.name / otel.service.name). Library-owned identifiers — the
# aipersimmon.process.manager.* metric names and the command */process.advance */outbox.publish */
# effect.dispatch * span-name prefixes — are NOT placeholders and are left untouched.
#
# Alert rules (alerts/*.json) import only when SIGNOZ_ALERT_CHANNEL is set, because a rule must
# reference an env-specific notification channel (SigNoz rejects a rule with no channel). The
# channel name is injected into every rule's preferredChannels at import.
#
# Usage (local SigNoz shown; each env sets its own URL/creds/orgID via secrets):
#   SIGNOZ_URL=http://localhost:8080 \
#   SIGNOZ_EMAIL=you@example.com SIGNOZ_PASSWORD='...' \
#   SIGNOZ_ORG_ID=<org-uuid> \
#   SIGNOZ_APP_NAME=<your-service-name> \
#   SIGNOZ_ALERT_CHANNEL=<a-configured-notification-channel> \
#   ./import.sh
#
# SIGNOZ_ORG_ID is per-environment; get it from an authenticated GET /api/v1/orgs/me.
set -euo pipefail
cd "$(dirname "$0")"

: "${SIGNOZ_URL:?set SIGNOZ_URL}"
: "${SIGNOZ_EMAIL:?set SIGNOZ_EMAIL}"
: "${SIGNOZ_PASSWORD:?set SIGNOZ_PASSWORD}"
: "${SIGNOZ_ORG_ID:?set SIGNOZ_ORG_ID}"
: "${SIGNOZ_APP_NAME:?set SIGNOZ_APP_NAME (the service name; substituted for __APP__)}"

python3 - "$SIGNOZ_URL" "$SIGNOZ_EMAIL" "$SIGNOZ_PASSWORD" "$SIGNOZ_ORG_ID" "${SIGNOZ_ALERT_CHANNEL:-}" "$SIGNOZ_APP_NAME" <<'PY'
import sys, json, glob, urllib.request, urllib.error
base, email, password, org, channel, app = sys.argv[1:7]

def load(path):
    """Read a checked-in JSON, substituting the __APP__ display-identity placeholder."""
    return json.loads(open(path).read().replace("__APP__", app))

def call(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    h = {"Content-Type": "application/json"}
    if token:
        h["Authorization"] = "Bearer " + token
    req = urllib.request.Request(base + path, data=data, method=method, headers=h)
    try:
        return 200, json.loads(urllib.request.urlopen(req, timeout=20).read().decode() or "{}")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()

_, login = call("POST", "/api/v2/sessions/email_password",
                {"email": email, "password": password, "orgID": org})
token = login["data"]["accessToken"]

_, existing = call("GET", "/api/v1/dashboards", token=token)
by_title = {(d["data"]["data"].get("title") if "data" in d["data"] else d["data"].get("title")): d["id"]
            for d in existing["data"]}

for path in sorted(glob.glob("dashboards/*.json")):
    obj = load(path)
    title = obj["title"]
    if title in by_title:
        code, r = call("PUT", "/api/v1/dashboards/" + by_title[title], obj, token)
        print(f"updated  {title!r} <- {path} [{code}]")
    else:
        code, r = call("POST", "/api/v1/dashboards", obj, token)
        print(f"created  {title!r} <- {path} [{code}]")
    if code != 200:
        print("  ERROR:", str(r)[:300]); sys.exit(1)

if channel:
    for path in sorted(glob.glob("alerts/*.json")):
        rule = load(path)
        rule["preferredChannels"] = [channel]
        code, r = call("POST", "/api/v1/rules", rule, token)
        print(f"alert    {rule['alert']!r} <- {path} [{code}]")
        if code != 200:
            print("  ERROR:", str(r)[:300]); sys.exit(1)
else:
    print("(skipping alerts/ — set SIGNOZ_ALERT_CHANNEL to a configured notification channel to import them)")
PY
