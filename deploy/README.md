# Phase 8: containers, Kubernetes, CI

```
deploy/
  docker-compose.yml        infrastructure: MySQL, Redis, Kafka (phase 1)
  docker-compose.app.yml    overlay: booking, payment, notification, nginx   <- use BOTH files
  nginx/                    edge proxy config (per-IP auth rate limit, only /api and /healthz reachable)
  k8s/                      Kubernetes manifests (kustomize)   -> deploy/k8s/README.md
  smoke-test.sh / .ps1      end-to-end check of a running stack
<service>/Dockerfile        one per service; build context is the REPO ROOT
.github/workflows/ci.yml    build+test+coverage gate, manifest validation, compose e2e, image publish
```

## A. Whole stack with Docker Compose (PowerShell, repo root)

```powershell
copy .env.example .env          # first time only; replace every CHANGE_ME value (JWT_SECRET needs 32+ characters)
docker compose --env-file .env -f deploy/docker-compose.yml -f deploy/docker-compose.app.yml up -d --build --wait
.\deploy\smoke-test.ps1         # expect: SMOKE TEST PASSED
```
First build takes several minutes (three Maven builds in containers); later builds reuse the cached dependencies.
Stop: `docker compose --env-file .env -f deploy/docker-compose.yml -f deploy/docker-compose.app.yml down` (add `-v` to wipe data).

- API through nginx: `http://localhost:8080` (change with `NGINX_PORT`; EC2 uses `NGINX_BIND=0.0.0.0 NGINX_PORT=80`).
- booking-service is also on `127.0.0.1:8081` for the k6 load test, because nginx's per-IP auth limit would throttle k6's 300 registrations.
- Logs: `docker compose ... logs -f booking-service`. Saga check: payment and notification logs show the event for the booking id.
- Check a container's hardening: `docker inspect flash-sale-booking-service-1 --format "{{.HostConfig.ReadonlyRootfs}}"` is true for the three services.

## B. Kubernetes on kind: see `deploy/k8s/README.md`.

## C. CI (GitHub Actions)

| Job | What it proves |
|---|---|
| build-test | `mvn verify`: every unit and Testcontainers test, plus the 80% coverage gate |
| validate-k8s | manifests render with kustomize and are valid against the Kubernetes schemas (no cluster needed) |
| e2e-compose | builds the 3 images, starts the whole stack, runs `smoke-test.sh` (login, book, idempotent replay, saga to CONFIRMED, logout) |
| publish-images | on `main` only, after the above: pushes `ghcr.io/<owner>/<repo>/<service>:<sha>` and `:latest` with the built-in `GITHUB_TOKEN` |

No secret is stored in the repo: e2e uses a throwaway `.env` with random values, publishing uses the workflow's own token.

## Design notes (the "why")
- **Multi-stage Dockerfile**: the JDK and Maven stay in the build stage; the runtime image is a JRE on Alpine, running as uid 10001 (Kubernetes' `runAsNonRoot` needs a number). Smaller image, smaller attack surface.
- **`exec java` as PID 1**: SIGTERM reaches Spring, so graceful shutdown finishes in-flight bookings during a rollout.
- **Heap from the container limit** (`MaxRAMPercentage=70`) instead of a fixed `-Xmx`; `ExitOnOutOfMemoryError` so the orchestrator restarts a broken JVM instead of leaving it half-alive.
- **Tests are not run inside `docker build`**: they need Docker (Testcontainers) and belong in CI; the image build stays fast.
- **Only nginx publishes a port**; MySQL, Redis, Kafka and the services are reachable only on the internal network.
- **nginx overwrites `X-Forwarded-For` with `$remote_addr`**: a client-supplied header must never reach the login rate limiter, or an attacker could dodge it by changing the header.
- **No Secret in git**: Kubernetes secrets are created from `.env` at deploy time.
- **Floating image tags** (`mysql:8.4`, `eclipse-temurin:21-jre-alpine`) keep the demo easy to read; production pins digests.
