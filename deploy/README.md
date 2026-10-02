# Deployment

CI builds an image tagged with the commit SHA and pushes it. A **manual** `deploy` job on
`main` then asks AWS SSM to run [`deploy.sh`](deploy.sh) on the instance, which pulls that
exact tag and restarts.

```
push ──► test ──► build (push :SHA, re-tag :latest) ──► deploy (manual)
                                                            │
                                                     SSM Send-Command
                                                            ▼
                                                  /opt/platform/deploy.sh <SHA>
```

## Why SSM rather than SSH

GitLab's shared runners have no fixed egress addresses. An SSH deploy would mean opening
port 22 to `0.0.0.0/0` and storing a private key in CI. SSM needs neither — the instance
keeps no inbound rule for deployment at all, and the runner authenticates to AWS instead.

## One-time setup

### 1. Files on the instance

```
/opt/platform/
├── docker-compose.yml     ← deploy/docker-compose.prod.yml
├── deploy.sh              ← deploy/deploy.sh        (root:root, 755)
├── tls-bootstrap.sh       ← deploy/tls-bootstrap.sh  (root:root, 755)
├── .env                   ← non-secret config       (ec2-user, 600)
└── nginx/prod.conf.template  ← nginx/prod.conf.template
```

`deploy.sh` is invoked by the SSM agent, which runs as root, so it must be root-owned and
executable. Everything secret is fetched at deploy time; nothing sensitive belongs in `.env`.

### 2. SSM parameters

```bash
aws ssm put-parameter --name /featureflag/prod/jwt-secret     --type SecureString --value "$(openssl rand -base64 32)"
aws ssm put-parameter --name /featureflag/prod/db-password    --type SecureString --value '<rds password>'
aws ssm put-parameter --name /featureflag/prod/registry-user  --type SecureString --value '<deploy token user>'
aws ssm put-parameter --name /featureflag/prod/registry-token --type SecureString --value '<deploy token>'
```

Create the registry credential as a GitLab **deploy token** (Settings → Repository → Deploy
tokens) with only `read_registry`. It is scoped to this project and revocable — unlike a
personal access token, which should never sit on a server.

### 3. Instance IAM role

Needs `AmazonSSMManagedInstanceCore` (so SSM can reach it at all) plus read access to those
four parameters:

```json
{
  "Effect": "Allow",
  "Action": ["ssm:GetParameter"],
  "Resource": "arn:aws:ssm:ap-south-2:<account>:parameter/featureflag/prod/*"
}
```

### 4. CI variables

Project → Settings → CI/CD → Variables. Mark both credentials **Masked** and **Protected**
so they are only exposed to protected branches.

| Variable | Value |
|---|---|
| `AWS_ACCESS_KEY_ID` | deploy user's key |
| `AWS_SECRET_ACCESS_KEY` | deploy user's secret |
| `AWS_DEFAULT_REGION` | `ap-south-2` |
| `EC2_INSTANCE_ID` | `i-…` |

The deploy IAM user needs only:

```json
{
  "Effect": "Allow",
  "Action": ["ssm:SendCommand", "ssm:GetCommandInvocation"],
  "Resource": [
    "arn:aws:ec2:ap-south-2:<account>:instance/<instance-id>",
    "arn:aws:ssm:ap-south-2::document/AWS-RunShellScript",
    "arn:aws:ssm:ap-south-2:<account>:*"
  ]
}
```

> **Worth upgrading later.** Long-lived access keys in CI are the weakest part of this setup.
> GitLab can assume an AWS role via OIDC instead (`id_tokens` + an IAM identity provider), which
> removes the stored credentials entirely. Same `deploy` job, different authentication.

## TLS

`nginx/prod.conf.template` serves HTTPS and redirects HTTP. It reads the hostname from
`API_DOMAIN`, so set that in `/opt/platform/.env` alongside the URLs:

```
API_DOMAIN=api.yourdomain.com
APP_BASE_URL=https://api.yourdomain.com
APP_CLIENT_BASE_URL=https://yourdomain.com
```

Point an `A` record for that hostname at the instance's elastic IP, then issue the first
certificate:

```bash
sudo API_DOMAIN=api.yourdomain.com /opt/platform/tls-bootstrap.sh you@yourdomain.com
```

That script exists because of a loop: nginx is configured to load a certificate that does
not exist yet, so it will not start — and certbot's webroot mode needs nginx already
serving. It issues the first certificate in **standalone** mode with port 80 free, before
nginx starts, then installs a twice-daily **webroot** renewal that needs no downtime.

It also checks that the domain resolves to this host first, because certbot's own failure in
that case complains about authorization rather than DNS and sends people looking in the wrong
place.

**Confirm renewal works before trusting it** — a silent renewal failure surfaces as an
expired certificate 90 days later:

```bash
docker run --rm -v /etc/letsencrypt:/etc/letsencrypt -v /var/www/certbot:/var/www/certbot \
  certbot/certbot renew --webroot -w /var/www/certbot --dry-run
```

Two notes on the config:

- **HSTS is set to one year without `preload`.** Preloading is effectively irreversible, so
  leave it off until the certificate has renewed successfully at least once.
- **The ACME challenge location must stay on plain HTTP** and must not be redirected. It is
  how Let's Encrypt proves domain control on every renewal.

## Mail

SES needs the domain verified before it will leave the sandbox:

1. SES → Verified identities → verify the **domain**, and publish the DKIM records it gives you
2. SES → SMTP settings → create SMTP credentials (not IAM keys)
3. SES → Account dashboard → **request production access**

Until that request is granted, SES delivers only to individually verified addresses.
Invitations to anyone else are rejected — and because mail is sent on a background thread,
**the API still returns success** and the failure appears only in the application log. Do not
read a 200 as "the invitation was delivered" while still in the sandbox.

## Deploying

Merge to `main`, then press **Deploy** on the pipeline. The job prints the remote output and
fails if the application does not answer within 150 seconds.

## Rolling back

Run the `deploy` job again with `DEPLOY_TAG` set to an earlier short SHA — pipeline →
**Run job** → add the variable. Or from the instance:

```bash
sudo /opt/platform/deploy.sh <older-sha>
```

`deploy.sh` records the running tag in `/opt/platform/.current-tag`, and prints the rollback
command if a deploy fails to come up.

**This does not roll back the database.** Schema is managed by `ddl-auto` with no migration
tooling, so a release that changed the schema cannot be undone by redeploying the old image.
Take a database snapshot before any deploy that touches entities.

## Known gaps

- **No health endpoint.** The project has no actuator dependency, so `deploy.sh` infers
  readiness by calling the login endpoint and treating any HTTP response as "serving".
  Adding `spring-boot-starter-actuator` and probing `/actuator/health` would be strictly
  better, and would let Docker restart the container on its own.
- **Brief downtime on deploy.** One container, replaced in place. Zero-downtime needs a
  second instance and a load balancer.
- **Test job runs on every branch** but the build only pushes `latest` on the default branch.
  Feature branches still push their SHA tag, which is useful for testing and does accumulate —
  prune the registry periodically.
