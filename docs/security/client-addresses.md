# Client addresses: watermarks, audit log and rate limits

The viewer's IP address is printed on every watermarked page and download (DOC-04) and stored with every audit
entry (ADM-03). Kong also rate-limits per address. That address must be the one our own proxies saw. An address the
caller wrote into `X-Forwarded-For` must never be used, or a user could put someone else's IP on a leaked document.

## Request path

```
Browser ──▶ [cloud load balancer] ──▶ Kong (app.*) ──▶ Frontend BFF ──▶ Kong (in-cluster) ──▶ Backend
                                                           │
Direct API caller ──▶ [cloud load balancer] ──▶ Kong (api.*) ─────────────────────────────▶ Backend
```

Each proxy appends the address it received the request from to `X-Forwarded-For`. Entries further left were written
by the caller. So every hop reads the chain **from the right** and skips only proxies it trusts.

| Hop | Setting | Behaviour |
|-----|---------|-----------|
| Kong | `trusted_ips`, `real_ip_header: X-Forwarded-For`, `real_ip_recursive: on` | From trusted peers (our pods, the load balancer) Kong keeps the chain and takes the right-most untrusted entry as the client, which `limit_by: ip` then uses. From anyone else it replaces the header with the peer's address. |
| Frontend BFF | `TRUSTED_PROXY_HOPS` | Takes the entry that many places from the right (`clientAddress`) and sends it as the only `X-Forwarded-For` value to the API. `0` = browsers connect directly (local); `1` = behind Kong. |
| Backend | `server.forward-headers-strategy: native` | Tomcat `RemoteIpValve` walks the chain from the right, skips internal proxies (private ranges by default, `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES` to override) and uses the first other address as `getRemoteAddr()`. |

`ClientAddressIntegrationTest` (backend) and `clientAddress.test.ts` (frontend) cover the rules.

## Kubernetes: Kong release settings

The Kong Ingress Controller is installed as a cluster add-on (not in this repository). Its Helm values must include:

```yaml
env:
  trusted_ips: "<pod CIDR>,<node CIDR / load balancer subnets>"
  real_ip_header: X-Forwarded-For
  real_ip_recursive: "on"
proxy:
  annotations: {}            # L7 load balancer: it appends the client address itself
  externalTrafficPolicy: Local # L4 load balancer: keeps the client address as the TCP peer (no SNAT)
```

Without these settings Kong replaces `X-Forwarded-For` with the frontend pod's address. Every watermark and audit
entry then shows the pod, and all users share one rate-limit bucket.

## Known limits

- Users behind one corporate NAT share a rate-limit bucket (600 requests/minute). Tune `minute` from the load
  tests (`tests/performance`) before go-live.
- If client addresses are themselves private (an on-premises deployment, ADR-0003), narrow
  `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES` and Kong's `trusted_ips` to the actual proxy subnets.
