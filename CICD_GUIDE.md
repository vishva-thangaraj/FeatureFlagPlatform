# CI/CD Pipeline Guide: GitLab CI + Docker + OCI Always Free

## Architecture Overview

```
GitLab Repo (push to main)
    │
    ▼
┌─── test ────────────────┐
│ ./gradlew test           │  ← Runs unit tests (auto)
└──────────┬──────────────┘
           ▼
┌─── secret-detection ────┐
│ GitLab SAST scan         │  ← Checks for leaked secrets (auto)
└──────────┬──────────────┘
           ▼
┌─── build ───────────────┐
│ docker build (ARM64)     │  ← Builds Docker image (auto, main only)
│ docker push to OCIR      │  ← Pushes to OCI Container Registry
└──────────┬──────────────┘
           ▼
┌─── deploy (manual) ─────┐
│ SSH to OCI VM            │  ← Click "Deploy" button in GitLab
│ docker-compose pull      │  ← Pulls new image on VM
│ docker-compose up -d     │  ← Restarts app (DB stays running)
└─────────────────────────┘
```

## Prerequisites

### 1. OCI Account Setup

You need an Oracle Cloud Always Free account with:
- **ARM A1 Flex VM** (up to 4 OCPUs, 24GB RAM)
- **OCI Container Registry** repository created

### 2. Create OCIR Repository

1. Go to **OCI Console → Developer Services → Container Registry**
2. Click **Create Repository**
3. Name: `feature-flag/platform`
4. Access: **Private**

### 3. Generate OCI Auth Token

1. Go to **OCI Console → Identity → Users → Your User**
2. Click **Auth Tokens → Generate Token**
3. Save the token — you can't see it again

### 4. Create ARM VM

1. Go to **OCI Console → Compute → Instances → Create Instance**
2. Image: **Ubuntu 22.04** (or 24.04)
3. Shape: **VM.Standard.A1.Flex** (ARM)
4. OCPUs: 2, Memory: 12GB (leave room for growth)
5. Add your SSH public key
6. Note the **public IP** after creation

### 5. Configure OCI Security List

Allow incoming traffic on port 8080:
1. Go to **Networking → Virtual Cloud Networks → Your VCN → Security Lists**
2. Add **Ingress Rule**: Source `0.0.0.0/0`, TCP, Port `8080`

## OCI VM One-Time Setup

SSH into your VM and run:

```bash
# 1. Install Docker
sudo apt-get update
sudo apt-get install -y docker.io docker-compose-plugin
sudo usermod -aG docker ubuntu
# Log out and back in for group change to take effect
exit
# SSH back in

# 2. Create app directory
sudo mkdir -p /opt/platform
sudo chown ubuntu:ubuntu /opt/platform

# 3. Open port 8080 in iptables (OCI Linux firewall)
sudo iptables -I INPUT -p tcp --dport 8080 -j ACCEPT
sudo apt-get install -y iptables-persistent
# Say "Yes" when asked to save rules
```

Then copy files to the VM:
```bash
# From your local machine
scp docker-compose.prod.yml ubuntu@<VM_IP>:/opt/platform/
scp .env.prod.example ubuntu@<VM_IP>:/opt/platform/.env
# Edit .env on the VM with real values
ssh ubuntu@<VM_IP> "nano /opt/platform/.env"
```

## GitLab CI/CD Variables

Go to **GitLab → Your Project → Settings → CI/CD → Variables** and add:

| Variable | Type | Protected | Masked | Value |
|---|---|---|---|---|
| `OCI_REGION` | Variable | Yes | No | `ap-mumbai-1` |
| `OCI_TENANCY_NAMESPACE` | Variable | Yes | No | Your namespace (find in OCI Console → Tenancy) |
| `OCI_REPO_NAME` | Variable | Yes | No | `feature-flag` |
| `OCI_USERNAME` | Variable | Yes | Yes | `<namespace>/<username>` |
| `OCI_AUTH_TOKEN` | Variable | Yes | Yes | Your auth token from step 3 |
| `OCI_SSH_PRIVATE_KEY` | Variable | Yes | Yes | Content of your SSH private key |
| `OCI_VM_HOST` | Variable | Yes | No | VM public IP |
| `OCI_VM_USER` | Variable | Yes | No | `ubuntu` |
| `OCI_VM_HOST_KEY` | Variable | Yes | No | Run: `ssh-keyscan <VM_IP>` and paste output |

### How to get OCI_VM_HOST_KEY
```bash
ssh-keyscan <VM_IP> 2>/dev/null
```
Copy the entire output (all lines) into the variable.

## Usage

### First Deployment
```bash
# On the VM, manually start for the first time:
cd /opt/platform
docker login <region>.ocir.io -u "<namespace>/<username>"
# Enter your auth token as password

docker-compose -f docker-compose.prod.yml up -d
docker-compose -f docker-compose.prod.yml logs -f app
```

### Subsequent Deployments
1. Push code to `main` branch
2. Pipeline runs: test → secret-detection → build automatically
3. Go to **GitLab → CI/CD → Pipelines → Latest pipeline**
4. Click the **Deploy** play button on the deploy stage
5. Wait for it to complete

### Rollback
```bash
# On the VM — rollback to a specific commit SHA:
cd /opt/platform
docker-compose -f docker-compose.prod.yml down app
docker pull <region>.ocir.io/<namespace>/feature-flag/platform:<commit-sha>
docker tag <region>.ocir.io/<namespace>/feature-flag/platform:<commit-sha> \
           <region>.ocir.io/<namespace>/feature-flag/platform:latest
docker-compose -f docker-compose.prod.yml up -d app
```

### View Logs
```bash
# On the VM:
docker-compose -f docker-compose.prod.yml logs -f app        # App logs
docker-compose -f docker-compose.prod.yml logs -f db          # DB logs
docker-compose -f docker-compose.prod.yml ps                  # Container status
```

## Files Reference

| File | Purpose | Committed? |
|---|---|---|
| `.gitlab-ci.yml` | CI/CD pipeline definition | Yes |
| `Dockerfile` | Multi-stage Docker build | Yes |
| `docker-compose.yml` | Local development | Yes |
| `docker-compose.prod.yml` | Production deployment | Yes |
| `.env.prod.example` | Template for prod secrets | Yes |
| `.env` | Actual secrets (on VM only) | **No** (gitignored) |
