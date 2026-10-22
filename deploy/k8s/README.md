# Kubernetes (kind) runbook, PowerShell, repo root

Needs: Docker Desktop, `kind`, `kubectl` (`winget install Kubernetes.kind Kubernetes.kubectl`).
Memory: the stack needs about 3.5 GB. Stop the compose stack first (`down`), and give Docker Desktop at least 5 GB.

```powershell
# 1. cluster + images (kind cannot see your local Docker images until you load them)
kind create cluster --name flash-sale
docker build -f booking-service/Dockerfile      -t flash-sale/booking-service:local .
docker build -f payment-service/Dockerfile      -t flash-sale/payment-service:local .
docker build -f notification-service/Dockerfile -t flash-sale/notification-service:local .
kind load docker-image flash-sale/booking-service:local flash-sale/payment-service:local flash-sale/notification-service:local --name flash-sale

# 2. namespace, then the things that must NOT live in git
kubectl apply -f deploy/k8s/00-namespace.yaml
kubectl create secret generic app-secrets -n flash-sale --from-env-file=.env
kubectl create configmap mysql-init -n flash-sale --from-file=init-databases.sh=deploy/mysql/init-databases.sh

# 3. everything else
kubectl apply -k deploy/k8s
kubectl get pods -n flash-sale -w        # wait until all are Running and 1/1 Ready (2-4 minutes)

# 4. test it: forward the service to your laptop and run the smoke test against it
kubectl port-forward -n flash-sale svc/booking-service 8081:8081      # leave running, open a second terminal
.\deploy\smoke-test.ps1 -BaseUrl http://localhost:8081 -HealthPath /actuator/health/readiness
```
The secret needs these keys in `.env`: `MYSQL_ROOT_PASSWORD`, `BOOKING_DB_PASSWORD`, `PAYMENT_DB_PASSWORD`, `NOTIFICATION_DB_PASSWORD`,
`REDIS_PASSWORD`, `JWT_SECRET`, `ADMIN_USERNAME`, `ADMIN_PASSWORD`. A pod stuck in `CreateContainerConfigError` means one is missing.

Useful: `kubectl logs -n flash-sale deploy/booking-service`, `kubectl describe pod -n flash-sale <pod>`, `kubectl get events -n flash-sale --sort-by=.lastTimestamp`.
Rolling update: rebuild + `kind load` the image with a new tag, `kustomize edit set image` or edit `kustomization.yaml`, `kubectl apply -k deploy/k8s`, watch zero-downtime with a loop of `curl /actuator/health/readiness`.
Delete everything: `kind delete cluster --name flash-sale`.

## Decisions
- **Probes**: `startupProbe` gives Spring + Flyway up to 3 minutes before liveness can kill it; `readinessProbe` takes a pod out of the Service when the DB is down, `livenessProbe` restarts a wedged JVM. Separate endpoints, separate jobs.
- **`enableServiceLinks: false`** on every pod: Kubernetes would otherwise inject `KAFKA_PORT=tcp://...` and `REDIS_PORT=tcp://...`, which Kafka's image and Spring misread as their own settings.
- **`maxUnavailable: 0` + `preStop: sleep 5` + 45 s grace period**: no request lands on a terminating pod and in-flight bookings finish.
- **Memory limit but no CPU limit**: CPU limits throttle the JVM at startup and add latency; the memory limit protects the node. OOMKilled (exit 137) means raise it.
- **Autoscaling booking-service** (HPA, 1 to 4) is safe because correctness lives in the database (unique index, `SKIP LOCKED` relay), not in the pod. It needs metrics-server, which kind does not ship.
- **MySQL, Redis and Kafka run in-cluster only for the demo.** Production uses managed services (RDS, ElastiCache, MSK): databases in Kubernetes need backups, failover and upgrades someone has to own.
- **NetworkPolicies are not included**: kind's default network plugin does not enforce them, so they could not be tested here.
