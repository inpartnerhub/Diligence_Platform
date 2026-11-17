# KEDA scale-to-zero for the ingestion worker (opt-in)

Takes `diligence-ingestion` to **zero replicas** when no uploads are waiting and scales it up with the RabbitMQ
backlog. Uploads queue durably in the quorum queue while the worker is absent, so nothing is lost — only the first
document after an idle period waits for a cold start. This is the only document-processing workload safe to scale to
zero; the synchronous HTTP services (Tika, Gotenberg, ClamAV) stay warm and only ride the Spot pool for savings.

Not wired into any overlay, so a cluster without KEDA keeps deploying unchanged.

## Enable

1. Install the KEDA operator (e.g. `helm install keda kedacore/keda -n keda --create-namespace`).
2. Provide an `AMQP_URI` secret key (`amqps://<user>:<pass>@<host>:5671/<vhost>`) in the `diligence-backend-infrastructure`
   secret — add it to the Vault path `diligence/<env>/backend/infrastructure` (host/user/pass are already there).
3. Add this component to the environment overlay and remove the static replica count so KEDA owns it:

   ```yaml
   # infrastructure/kubernetes/overlays/<env>/kustomization.yaml
   resources:
     - ../../base
     - ../../components/keda-ingestion
   ```

## Verify

```sh
kubectl get scaledobject -n diligence
kubectl get hpa -n diligence            # KEDA creates a managed HPA
# Upload a document and watch the worker scale 0 -> N, then back to 0 after the cooldown.
```
