# Client addresses: watermarks, audit log and rate limits

The viewer's IP address is printed on every watermarked page and download (DOC-04) and stored with every audit
entry (ADM-03). The NGINX Ingress also rate-limits per address. That address must be the one our own proxies saw. An
address the caller wrote into `X-Forwarded-For` must never be used, or a user could put someone else's IP on a leaked
document.

## Request path

```
Browser ──▶ [cloud load balancer] ──▶ NGINX Ingress (app.*) ──▶ Frontend BFF ──▶ Backend
Direct API caller ──▶ [cloud load balancer] ──▶ NGINX Ingress (api.*) ──────────▶ Backend
```

Each proxy appends the address it received the request from to `X-Forwarded-For`. Entries further left were written
by the caller. So every hop reads the chain **from the right** and skips only proxies it trusts.

| Hop | Setting | Behaviour |
|-----|---------|-----------|
| NGINX Ingress | `use-forwarded-headers`, `proxy-real-ip-cidr`, `compute-full-forwarded-for` | From trusted peers (the load balancer subnets in `proxy-real-ip-cidr`) it keeps the forwarded chain and appends the connection peer; from anyone else it uses the connection peer as the client. That client address is what the `limit-rpm` per-IP limit uses and what it forwards upstream. |
| Frontend BFF | `TRUSTED_PROXY_HOPS` | Takes the entry that many places from the right (`clientAddress`) and sends it as the only `X-Forwarded-For` value to the API. `0` = browsers connect directly (local); `1` = behind the ingress. |
| Backend | `server.forward-headers-strategy: native` | Tomcat `RemoteIpValve` walks the chain from the right, skips internal proxies (private ranges by default, `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES` to override) and uses the first other address as `getRemoteAddr()`. |

`ClientAddressIntegrationTest` (backend) and `clientAddress.test.ts` (frontend) cover the rules.

## Kubernetes: NGINX Ingress Controller settings

The NGINX Ingress Controller is installed as a cluster add-on (not in this repository). Its ConfigMap must trust the
load balancer and preserve the real client address (match the exact CIDRs to the cluster's networking):

```yaml
# ingress-nginx controller ConfigMap
use-forwarded-headers: "true"
compute-full-forwarded-for: "true"
proxy-real-ip-cidr: "<pod CIDR>,<node CIDR / load balancer subnets>"
```

```yaml
# controller Service: preserve the client source IP at L4 (no SNAT)
externalTrafficPolicy: Local
```

Without these settings the ingress rewrites `X-Forwarded-For` with the connecting peer's address. Every watermark and
audit entry then shows a proxy address, and all users share one rate-limit bucket.

## Known limits

- Users behind one corporate NAT share a rate-limit bucket (600 requests/minute via the ingress `limit-rpm`
  annotation). The ingress keeps the counters in each controller pod's shared memory rather than a shared store, so
  with more than one controller replica the effective per-IP limit scales with the replica count — account for that
  and tune `limit-rpm` from the load tests (`tests/performance`) before go-live. The backend's per-user limit (#63)
  is independent and shared across replicas through Redis.
- If client addresses are themselves private (an on-premises deployment, ADR-0003), narrow
  `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES` and `proxy-real-ip-cidr` to the actual proxy subnets.
