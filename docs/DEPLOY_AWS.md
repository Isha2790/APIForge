# Deploying to AWS (guide, not automated or tested here)

The simplest honest path for a portfolio is one EC2 instance running Docker Compose behind HTTPS.
Use managed Postgres/Redis only if you want to show that skill; the app needs nothing else.

## Option A: single EC2 instance (cheap, quick)
1. Launch an Ubuntu EC2 instance (t3.small or larger). Security group: allow 22 (your IP only), 80 and 443; **do not** open 8081, 3001, 9090.
2. Install Docker and the Compose plugin, clone the repo, run `bash scripts/gen-env.sh`.
3. In `compose.yaml` for production: remove `ALLOWED_PRIVATE_HOSTS` / `ALLOW_PRIVATE_HOSTS` and the `demo-api` service, and set the frontend build args to your public URLs (`NEXT_PUBLIC_API_URL`, `NEXT_PUBLIC_GATEWAY_URL`) and management `CORS_ORIGINS` to the dashboard origin.
4. Put a reverse proxy with TLS (Caddy or nginx + Let's Encrypt) in front: dashboard on one hostname, gateway on another, management API on a third that does **not** expose `/internal/**`.
5. `docker compose up -d --build`; watch `docker compose logs -f management gateway`.
6. Backups: a nightly `pg_dump` to S3; Redis holds only caches and a bounded event stream.

## Option B: managed services
* RDS PostgreSQL (private subnet) and ElastiCache Redis (private subnet, in-transit encryption), pass endpoints through `DATABASE_URL` and `REDIS_HOST`.
* Run the three containers on ECS Fargate (push images to ECR), two gateway tasks behind an Application Load Balancer, secrets in AWS Secrets Manager.
* When behind a load balancer, add trusted-proxy handling so per-IP throttling uses the real client address.

## Production checklist
* Real random secrets (`JWT_SECRET` at least 32 bytes, `INTERNAL_SECRET`), never the defaults in `application.yml`.
* HTTPS everywhere; the management port reachable only from your reverse proxy and the gateways.
* Alert on gateway 503 rate, p95 latency, and Redis memory (Grafana dashboard provided).
* Rotate secrets and test restore of the database backup before you rely on it.
* Email: remove the `mailpit` service and set `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_AUTH=true`, `SMTP_STARTTLS=true` (Amazon SES, or any SMTP provider), `MAIL_FROM` to a verified sender, and `PUBLIC_URL` to the HTTPS address of the dashboard, otherwise reset links point at localhost.
