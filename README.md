# NAGP GKE Multi-Tier Assignment

This repository contains a Java 21 Spring Boot API tier and a PostgreSQL database tier designed for deployment on Google Kubernetes Engine.

## Repository And Images

- Code repository: `https://github.com/syed-waquar-11/nagp26-workshop`
- Artifact Registry image: `us-central1-docker.pkg.dev/nagp26-assignment/catalog-api/catalog-api:1.0.0`
- Service API URL after GKE Ingress provisioning: `http://catalog-api.example.com/api/products`
- Screen recording: 2 Parts because of large size
- Part 1: https://drive.google.com/file/d/1yTUt1_JdcE2yqZ_QbciKaP8W0b1rtUW_/view?usp=sharing
- Part 2: https://drive.google.com/file/d/1lJ0mHYUz3wYRJyghWeHdSftV8_9OJCtJ/view?usp=sharing

## Architecture

- API tier: Spring Boot 3.5.15, Java 21, layered Controller-Service-Repository code, JDBC, HikariCP connection pooling, 4 pods, rolling update strategy, HPA, external access through GKE Ingress.
- Database tier: PostgreSQL 16, 1 pod, StatefulSet, PersistentVolumeClaim, ClusterIP-only internal access.
- Database schema and seed data: Flyway migrations under `src/main/resources/db/migration`.
- Configuration: API profile, database host, port, database name, and pool size come from environment-specific `ConfigMap` files.
- Secrets: API and database credentials come from environment-specific Kubernetes `Secret` files.

## API

```text
GET /api/products       — fetch all products
GET /api/products/{id}  — fetch a single product by ID
GET /actuator/info      — application metadata (name, version, Java, Spring Boot)
GET /actuator/health    — health check (liveness/readiness probes)
```

Example response for `GET /api/products`:

```json
{
  "timestamp": "2026-06-23T10:00:00Z",
  "count": 7,
  "records": [
    {
      "id": 1,
      "name": "Wireless Mouse",
      "category": "Accessories",
      "price": 799.00,
      "stockQuantity": 24
    }
  ]
}
```

Example response for `GET /api/products/1`:

```json
{
  "timestamp": "2026-06-23T10:00:00Z",
  "record": {
    "id": 1,
    "name": "Wireless Mouse",
    "category": "Accessories",
    "price": 799.00,
    "stockQuantity": 24
  }
}
```

Error responses use a consistent JSON format:

```json
{
  "timestamp": "2026-06-23T10:00:00Z",
  "status": 404,
  "error": "Not Found",
  "message": "Product not found with id: 999"
}
```

## Local Build

```powershell
mvn clean test
mvn clean package
```

## Enable, Create and Authenticate with Artifact Registry

```powershell
gcloud services enable cloudbuild.googleapis.com artifactregistry.googleapis.com

gcloud artifacts repositories create catalog-api `
  --repository-format=docker `
  --location=us-central1 `
  --description="Catalog API container images"

gcloud auth configure-docker us-central1-docker.pkg.dev
```

## Build And Push Image With Cloud Build

```powershell
gcloud auth configure-docker us-central1-docker.pkg.dev
gcloud builds submit --tag us-central1-docker.pkg.dev/nagp26-assignment/catalog-api/catalog-api:1.0.0 .
gcloud artifacts docker images list us-central1-docker.pkg.dev/nagp26-assignment/catalog-api --include-tags
```

Update the image in `k8s/04-api.yaml`:

```yaml
image: us-central1-docker.pkg.dev/nagp26-assignment/catalog-api/catalog-api:1.0.0
```

## Deploy Dev To GKE

Create or use a GKE cluster with the metrics server enabled. Then deploy:

```powershell
kubectl apply -f k8s/environments/dev/environment.yaml
kubectl apply -n nagp-dev -f k8s/03-postgres.yaml
kubectl apply -n nagp-dev -f k8s/04-api.yaml
kubectl apply -n nagp-dev -f k8s/05-hpa.yaml
kubectl apply -n nagp-dev -f k8s/08-network-policy.yaml
kubectl apply -f k8s/environments/dev/ingress.yaml
kubectl get all -n nagp-dev
kubectl get ingress -n nagp-dev
```


Once the Ingress gets an external IP, map your DNS host to that IP or test directly with a host header:

```powershell
curl -H "Host: catalog-api-dev.example.com" http://8.233.6.247/api/products | ConvertFrom-Json | ConvertTo-Json
```


## Demonstration Commands

Show all objects:

```powershell
kubectl get all,ingress,pvc,configmap,secret,hpa,pdb -n nagp-dev
```

Call the API:

```powershell
curl -H "Host: catalog-api-dev.example.com" http://8.233.6.247/api/products
```

Kill an API pod to show self-healing:

```powershell
kubectl delete pod -n nagp-dev -l app=catalog-api
kubectl get pods -n nagp-dev -w
```

Kill the database pod to show recovery with persisted data:

```powershell
kubectl delete pod -n nagp-dev postgres-0
kubectl get pods -n nagp-dev -w
curl -H "Host: catalog-api-dev.example.com" http://8.233.6.247/api/products
```

Trigger HPA demonstration:

```powershell
kubectl apply -n nagp-dev -f k8s/07-load-generator.yaml
kubectl get hpa -n nagp-dev -w
kubectl top pods -n nagp-dev
```

Show rolling update support:

```powershell
kubectl set image deployment/catalog-api catalog-api=us-central1-docker.pkg.dev/nagp26-assignment/catalog-api/catalog-api:1.0.1 -n nagp-dev
kubectl rollout status deployment/catalog-api -n nagp-dev
kubectl rollout history deployment/catalog-api -n nagp-dev
```

## FinOps — Cost Optimization

This project demonstrates five cloud cost optimization strategies relevant to the assignment and real-world GKE usage.

### 1. Requests & Limits — Prevents Over-Provisioning

```yaml
# k8s/04-api.yaml
resources:
  requests:
    cpu: 150m
    memory: 256Mi
  limits:
    cpu: 500m
    memory: 512Mi
```

**How it saves money:**
Requests guarantee a minimum amount of resources. Without them, Kubernetes can overpack pods onto a single node, causing CPU throttling under load. With explicit requests, the scheduler bin-packs efficiently — for example, 4 API pods × 150m CPU = 600m total — and fills each node to a healthy utilization level rather than wasting capacity.

**Without requests:** Pods fight for CPU -> slow responses -> you scale up unnecessarily, burning budget.<br>
**With requests:** Scheduler places pods predictably → fewer nodes needed -> lower bill.

### 2. HPA — Scales Under Load, Saves When Idle

```yaml
# k8s/05-hpa.yaml
minReplicas: 4
maxReplicas: 8
metrics:
  - resource:
      name: cpu
      target:
        averageUtilization: 60
```

**How it saves money:**
- **Idle** (e.g., 3am, no users): 4 pods at 5% CPU -> cost $X/hour
- **Peak** (e.g., business hours): HPA scales to 8 pods -> cost $2X/hour

Without HPA you'd run 8 pods 24/7 to handle peak load, paying double during off-peak hours when demand is near zero.

### 3. ResourceQuota — Caps Total Namespace Resources

```yaml
# k8s/environments/dev/environment.yaml
spec:
  hard:
    requests.cpu: "2"
    requests.memory: 3Gi
    limits.cpu: "4"
    limits.memory: 6Gi
    pods: "20"
```

Acts as a **financial circuit breaker**. A buggy deployment (e.g., accidentally scaling to 100 replicas) is rejected immediately instead of provisioning expensive infrastructure. Without a quota, runaway resource consumption can burn through your budget in minutes.

### 4. Right-Sized PVC — 5Gi

```yaml
# k8s/03-postgres.yaml
resources:
  requests:
    storage: 5Gi
```

The product catalog is 7 rows (~100 KB). Even with years of growth, 5Gi is ample. Using `standard-rwo` at ~$0.17/GB/month, this costs ~**$0.85/month** compared to ~$17/month for a 100Gi volume — a 20× difference for the same workload.

### 5. Autopilot — Pay Per Pod, No Idle Node Costs

| Cluster Type | How billing works | Monthly cost (idle) |
|---|---|---|
| **Standard** | You pay for node VMs 24/7, even if empty | ~$50–100/month for 3 small nodes |
| **Autopilot** | You pay only for running pod resources | ~$20–40/month for 5 small pods |

With Autopilot, deleting the namespace drops costs to **$0** immediately. A Standard cluster keeps charging for the underlying VMs even with zero workloads.

### Summary

| Optimization | Impact |
|---|---|
| Requests & Limits | Bin-packs pods efficiently, needs fewer nodes |
| HPA | Scales down when idle, saves 30–40% off-peak |
| ResourceQuota | Prevents runaway cost from misconfiguration |
| Right-sized PVC | 5Gi vs 100Gi = 20× cheaper for same work |
| Autopilot | No idle node charges, pay only for what runs |



## Cleanup

```powershell
kubectl delete namespace nagp-dev
```
