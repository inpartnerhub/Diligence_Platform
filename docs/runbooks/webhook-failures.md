# Webhook deliveries failing

**Alert:** `DiligenceWebhookDeliveriesFailing` (counter `diligence_webhooks_delivery_failed_total`, labels
`subscription` and `reason`, #86).

Outbound webhooks (ADM-07) send platform events to a client's or partner's system. A delivery is tried up to 5 times
with backoff; when all attempts fail, the event is not sent again and the receiver misses it. Test pings from the
admin page are not counted.

| `reason` | Meaning |
|----------|---------|
| `failed` | The receiver answered non-2xx, timed out or refused the connection on every attempt. |
| `blocked` | The target resolved to a private, loopback, link-local or metadata address and the delivery was refused (SSRF protection, #87). |

## Check

1. Administration → Webhooks → the subscription with the id in the alert → *Deliveries*: status code, attempts and
   error per delivery. Or in the database:
   `SELECT event_type, status_code, attempt_count, error_message, delivered_at FROM adm.webhook_delivery_log
   WHERE subscription_id = '<id>' AND NOT success ORDER BY delivered_at DESC LIMIT 20;`
2. `failed` with `HTTP 401/403`: the receiver rejects the signature, usually after a signing-key rotation that the
   receiver did not pick up. `HTTP 5xx`, `HttpHostConnectException`, `SocketTimeoutException`: the receiver is down.
3. `blocked`: the receiver's DNS name now points to an internal address. That is either a DNS change on their side
   (moved behind a private endpoint) or an attempt to reach our internal network through the webhook. The target
   URL can only be set by a Super Admin with a fresh MFA sign-in: check the audit log for who changed it.

## Mitigate

- Receiver down or rejecting: contact the receiver's owner (the organization on the subscription). Once fixed, use
  *Send test* on the admin page to confirm.
- Missed events are not replayed automatically. The receiver can reconcile from the platform (the events are in the
  audit log); agree with them what they need.
- A subscription that stays broken: deactivate it so the alert stops, and tell the owner.
- `blocked` without a plausible DNS explanation: deactivate the subscription and treat it as a security incident
  ([incident-response.md](incident-response.md)).

## Escalation

Platform on-call for `failed`; Security Officer for `blocked`.
