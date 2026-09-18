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
├── deploy.sh              ← deploy/deploy.sh   (root:root, 755)
├── .env                   ← non-secret config  (ec2-user, 600)
└── nginx/prod.conf        ← nginx/prod.conf
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
