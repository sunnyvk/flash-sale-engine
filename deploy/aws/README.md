# Phase 9: deploy to AWS EC2 (single instance, Docker Compose, real HTTPS)

```
Internet ──443──▶ [EC2 t3.large, SG: 80/443 only]
                    nginx (TLS, rate limits) ─▶ booking ─▶ payment / notification
                    MySQL · Redis · Kafka (containers, internal network only)
  no SSH:  shell = SSM Session Manager · config = SSM Parameter Store · images = GHCR (built by CI) · backups = S3
```

Cost: roughly 8 to 10 USD/month for t3.large (ap-south-1) + EBS + Elastic IP while running. **Stop or tear down when idle**
(`teardown.sh`). A t3.medium (4 GB) is too tight: the stack needs about 3.5 GB.

## 0. Prerequisites
- The repo is on GitHub, **public** (so the instance can clone it and pull images without credentials) and CI has run
  green on `main` once (that publishes `ghcr.io/<owner>/<repo>/<service>` images). Package visibility: GitHub → your
  profile → Packages → each of the 3 packages → Settings → *Public*. (Private repo? Then add a read-only PAT login; out of scope here.)
- An AWS account, and **AWS CloudShell** open in the console (region ap-south-1 / Mumbai). No local AWS CLI needed.
- A domain name. Free option: <https://www.duckdns.org> gives `yourname.duckdns.org`. Let's Encrypt will not issue for a bare IP.

## 1. Provision (CloudShell)
```bash
git clone https://github.com/<you>/<repo>.git && cd <repo>/deploy/aws
REPO_URL=https://github.com/<you>/<repo>.git ./provision.sh
```
It prints the **Elastic IP**. Create a DNS `A` record `<your-domain> → that IP`, then check from your laptop:
`nslookup <your-domain>` must return the IP before step 4.

## 2. Configuration into SSM Parameter Store (CloudShell)
```bash
DOMAIN=<your-domain> LETSENCRYPT_EMAIL=<you@mail> GHCR_REPO=<github-user>/<repo> ./put-parameters.sh
```
Generates random hex passwords and JWT secret (SecureString, KMS-encrypted) and prints the admin password once.
Re-running never rotates existing secrets. Keep the demo data: the seed migration location from `.env.example`
(`BOOKING_FLYWAY_LOCATIONS`) can be added the same way if you want the flash-sale demo event:
`aws ssm put-parameter --name /flashsale/prod/BOOKING_FLYWAY_LOCATIONS --type String --value "<same value as your .env>"`.

## 3. Open a shell on the instance (no SSH)
Console → EC2 → instance `flashsale` → Connect → **Session Manager** (takes ~2 minutes after launch to register).
Check the bootstrap finished: `cat /var/log/flashsale-bootstrap.done`, then `sudo -i`.

## 4. Certificate, then deploy
```bash
cd /opt/flashsale/repo/deploy/aws
STAGING=1 ./issue-cert.sh     # dry run against Let's Encrypt's test CA: proves DNS + port 80 are right
./issue-cert.sh               # the real one (rate limit: 5 per week per domain, so do the staging run first)
./deploy.sh main              # or a 40-char commit sha: pulls the images CI built for exactly that commit
```
Verify from your laptop:
```powershell
curl.exe -I https://<your-domain>/healthz
curl.exe -I http://<your-domain>/api/v1/events          # 301 to https
Test-NetConnection <elastic-ip> -Port 22                # must FAIL (closed)
Test-NetConnection <elastic-ip> -Port 3306              # must FAIL
powershell -ExecutionPolicy Bypass -File .\deploy\smoke-test.ps1 -BaseUrl https://<your-domain>
```

## 5. What runs automatically
- `/etc/cron.d/flashsale`: certificate check daily 03:17 (renews only under 30 days left; nginx stops ~10 seconds), MySQL dump to S3 daily 02:30 (14-day expiry).
- Restore a backup: command in the header of `backup-mysql.sh`.

## 6. Optional: deploy from CI on every push to `main`
No AWS keys in GitHub. A role trusts GitHub's OIDC token **only** for `environment: production` of your repo and may only run commands on this one instance.
```bash
# CloudShell
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
aws iam create-open-id-connect-provider --url https://token.actions.githubusercontent.com --client-id-list sts.amazonaws.com   # once per account; ignore "already exists"
sed -e "s|\${ACCOUNT_ID}|$ACCOUNT_ID|g" -e "s|\${GITHUB_REPO}|<owner>/<repo>|g" iam/github-oidc-trust.json.template > /tmp/trust.json
aws iam create-role --role-name flashsale-gh-deploy --assume-role-policy-document file:///tmp/trust.json
sed -e "s|\${ACCOUNT_ID}|$ACCOUNT_ID|g" -e "s|\${REGION}|ap-south-1|g" -e "s|\${INSTANCE_ID}|<i-xxxx>|g" iam/github-deploy-policy.json.template > /tmp/pol.json
aws iam put-role-policy --role-name flashsale-gh-deploy --policy-name deploy --policy-document file:///tmp/pol.json
```
GitHub → Settings → Environments → create `production` (add yourself as required reviewer for a manual gate) → Variables:
`AWS_DEPLOY_ROLE_ARN` (the role ARN), `AWS_INSTANCE_ID`, optional `AWS_REGION`. Until the first variable exists the `deploy` job is skipped.

## 9. Optional: Prometheus + Grafana on the instance
```bash
# CloudShell, before deploying (or re-run later, then redeploy)
OBSERVABILITY=1 DOMAIN=... LETSENCRYPT_EMAIL=... GHCR_REPO=... ./put-parameters.sh     # prints the Grafana password once
```
`deploy.sh` then starts Prometheus and Grafana too (about 600 MB more RAM: fine on t3.large, too tight on t3.medium).
Neither is exposed to the internet: the security group still has only 80 and 443 open. View Grafana through an SSM port-forward from your laptop
(needs the AWS CLI and the Session Manager plugin installed locally):
```powershell
aws ssm start-session --target <i-xxxx> --document-name AWS-StartPortForwardingSession --parameters "portNumber=3000,localPortNumber=3000"
```
then open <http://localhost:3000>, user `admin`. The smoke test asserts that `/actuator/prometheus` is NOT reachable through the public proxy.

## 7. Rollback
`sudo /opt/flashsale/repo/deploy/aws/deploy.sh <previous-40-char-sha>`. Flyway migrations only go forward, so keep migrations backward compatible for one release.

## 8. Tear down
`./teardown.sh` (instance, IP, SG, IAM). `DELETE_DATA=1 ./teardown.sh` also deletes backups and SSM parameters.

## Things that are deliberately not here
- **Plain HTTP "just to demo"**: bad idea, because logins and JWTs would cross the internet in clear text. Use the free certificate.
- **Single instance**: one machine is a single point of failure and MySQL/Kafka are containers. Fine for a portfolio, and the honest answer in an interview is "production would use RDS, ElastiCache, MSK and an ALB with 2+ instances; the Kubernetes manifests in `deploy/k8s` show the scaled-out shape".
- Not verified in my sandbox: AWS calls, certbot and Docker could not run there. Scripts are shellcheck-clean and the nginx template is checked by the `validate-nginx-tls` CI job, but your first run is the real test, so use `STAGING=1` first.
