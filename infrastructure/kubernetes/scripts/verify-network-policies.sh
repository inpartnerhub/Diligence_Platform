#!/usr/bin/env bash
# Post-deployment check that NetworkPolicies are enforced (#157). The API server accepts NetworkPolicy objects even when
# nothing enforces them, so their presence proves nothing; this script tries connections the policies must block.
#   infrastructure/kubernetes/scripts/verify-network-policies.sh <environment namespace, e.g. diligence-staging>
# Exits non-zero if any forbidden connection succeeds. Read-only apart from short-lived exec sessions.
set -uo pipefail
app_ns=${1:?usage: $0 <application namespace, e.g. diligence-staging>}
processing_ns=diligence-processing
failures=0

# Runs curl inside an existing pod; a connection that succeeds (any HTTP status) means the policy is not enforced.
must_fail() { # description namespace workload url
  local description=$1 namespace=$2 workload=$3 url=$4
  if kubectl -n "$namespace" exec "$workload" -- curl -sS -o /dev/null -m 5 "$url" >/dev/null 2>&1; then
    echo "FAIL  $description: $workload reached $url"
    failures=$((failures + 1))
  else
    echo "ok    $description"
  fi
}

must_fail "processing pods have no internet egress" "$processing_ns" deploy/tika https://example.com
must_fail "processing pods cannot reach the backend" "$processing_ns" deploy/tika \
  "http://diligence-backend.$app_ns.svc:8080/actuator/health"
must_fail "processing pods cannot reach the cloud metadata endpoint" "$processing_ns" deploy/tika \
  http://169.254.169.254/latest/meta-data/
must_fail "Gotenberg has no internet egress" "$processing_ns" deploy/gotenberg https://example.com

# Runs a command, killing it after N seconds (macOS has no `timeout`); returns its exit status, or 124 on timeout.
within() { # seconds command...
  local seconds=$1; shift
  "$@" >/dev/null 2>&1 & local pid=$!
  for _ in $(seq 1 $((seconds * 10))); do
    kill -0 "$pid" 2>/dev/null || { wait "$pid"; return; }
    sleep 0.1
  done
  kill "$pid" 2>/dev/null; return 124
}

# Positive control: the backend must still reach the parsers, otherwise the checks above prove nothing. The backend
# image has no curl; bash's /dev/tcp opens the connection.
if within 10 kubectl -n "$app_ns" exec deploy/diligence-backend -- \
    bash -c "exec 3<>/dev/tcp/tika.$processing_ns.svc/9998"; then
  echo "ok    backend reaches Tika (positive control)"
else
  echo "FAIL  backend cannot reach Tika: the checks above are not conclusive"
  failures=$((failures + 1))
fi

if [ "$failures" -gt 0 ]; then
  echo "$failures check(s) failed: NetworkPolicies are not (fully) enforced."
  exit 1
fi
echo "NetworkPolicies are enforced."
