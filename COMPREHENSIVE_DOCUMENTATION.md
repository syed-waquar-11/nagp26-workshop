# Comprehensive Documentation

## Requirement Understanding

The goal is to design, containerize, and deploy a simple multi-tier application on Kubernetes for GKE. The application must have one Java microservice and one database. The service tier exposes an API externally, fetches records from the database tier, and demonstrates production-oriented Kubernetes behavior such as rolling updates, self-healing, autoscaling, configuration separation, secret usage, and resource optimization.

The database tier must stay internal to the cluster, run with persistent storage, initialize one table with 5 to 10 records, and recover automatically after pod deletion without losing data.

## Assumptions

- The target platform is GCP GKE Standard or Autopilot with a default `standard-rwo` storage class available.
- GKE metrics server is available so the HorizontalPodAutoscaler can read CPU and memory metrics.
- The API image will be built with GCP Cloud Build and pushed to Artifact Registry before deployment (no local Docker required).
- The provided Kubernetes Secret manifests use base64 encoded values for assignment repeatability. For production, secrets should be created through a secret manager, sealed secrets, or CI/CD secret injection.
- The domain `catalog-api.example.com` is a placeholder and should be replaced by a real DNS name mapped to the GKE Ingress external IP.
- GKE Autopilot is recommended for the free trial deployment to minimize cost (pay-per-pod instead of pay-per-node).

## Solution Overview

### Architecture Diagram

```
                            Internet
                                │
                                ▼
                     ┌──────────────────┐
                     │  GKE Ingress     │
                     │  (External LB)   │
                     │  8.233.6.247     │
                     └────────┬─────────┘
                              │ port 80
                              │ Host: catalog-api-dev.example.com
                              ▼
                     ┌──────────────────┐
                     │  Service         │
                     │  catalog-api     │
                     │  ClusterIP       │
                     │  34.118.226.104  │
                     └──┬────┬────┬─────┘
                        │    │    │
              ┌─────────┘    │    └─────────┐
              ▼              ▼              ▼
     ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
     │  API Pod     │ │  API Pod     │ │  API Pod     │
     │  1/4         │ │  2/4         │ │  3/4         │
     │  :8080       │ │  :8080       │ │  :8080       │
     └──────┬───────┘ └──────┬───────┘ └──────┬───────┘
            │                │                │
            └────────────────┼────────────────┘
                             │
                             ▼
                    ┌──────────────────┐
                    │  Service         │
                    │  postgres        │
                    │  ClusterIP       │
                    │  34.118.230.5    │
                    │  port 5432       │
                    └────────┬─────────┘
                             │
                             ▼
                    ┌──────────────────┐
                    │  StatefulSet     │
                    │  postgres-0      │
                    │  PostgreSQL 16   │
                    ├──────────────────┤
                    │  PVC: 5Gi        │
                    │  standard-rwo    │
                    └──────────────────┘

              ┌──────────────────────────────────┐
              │  NetworkPolicy                   │
              │  Only catalog-api pods can       │
              │  reach postgres on port 5432     │
              └──────────────────────────────────┘
```

### Request Flow

```
Client Request
     │
     ▼
┌────────────────────────────────────────────────────────────┐
│  GKE Ingress                                               │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  Host header: catalog-api-dev.example.com            │  │
│  │  Path: /api/products                                 │  │
│  │  ┌────────────────────────────────────────────────┐  │  │
│  │  │  Match rule → forward to catalog-api service   │  │  │
│  │  └────────────────────────────────────────────────┘  │  │
│  └──────────────────────────────────────────────────────┘  │
└──────────────────────────┬─────────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────────┐
│  catalog-api Pod                                           │
│                                                            │
│  ┌──────────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │ ProductController│─▶│ProductService│─▶│ProductRepo   │  │
│  │ @RestController  │  │ @Service     │  │ @Repository  │  │
│  └──────────────────┘  └──────────────┘  └──────┬───────┘  │
│    GET /api/products                            │          │
│    returns JSON                                 │ JDBC     │
│                                                  ▼         │
│                                         ┌──────────────┐   │
│                                         │  PostgreSQL  │   │
│                                         │  via HikariCP│   │
│                                         └──────────────┘   │
└────────────────────────────────────────────────────────────┘
                           │
                           ▼
               JSON Response to Client
```

### Kubernetes Resources Overview

```
Namespace: nagp-dev
│
├── ResourceQuota (2 CPU / 3Gi requests, 4 CPU / 6Gi limits, 20 pods)
│
├── ConfigMap: api-config
│   └── SPRING_PROFILES_ACTIVE, DB_HOST, DB_PORT, DB_NAME, pool sizes, etc.
│
├── Secret: postgres-secret
│   └── POSTGRES_USER, POSTGRES_PASSWORD, POSTGRES_DB
│
├── Secret: api-db-secret
│   └── DB_USERNAME, DB_PASSWORD
│
├── StatefulSet: postgres (1 replica)
│   ├── Service: postgres (ClusterIP :5432)
│   ├── PVC: postgres-data (5Gi standard-rwo)
│   └── NetworkPolicy: postgres-network-policy
│
├── Deployment: catalog-api (4 replicas → HPA 4-8)
│   ├── Service: catalog-api (ClusterIP :80)
│   ├── PodDisruptionBudget: minAvailable=3
│   ├── HPA: catalog-api-hpa (cpu:60%, mem:75%)
│   └── RollingUpdate: maxSurge=1, maxUnavailable=1
│
└── Ingress: catalog-api-ingress
    └── host: catalog-api-dev.example.com → service: catalog-api:80
```

The solution has two tiers:

1. Service/API tier: `catalog-api`
2. Database tier: `postgres`

The API tier is a Spring Boot 3.5.15 application running on Java 21. It uses a Controller-Service-Repository structure. `ProductController` handles HTTP requests, `ProductService` owns service-level product access, and `ProductRepository` runs SQL through Spring JDBC. It exposes two API endpoints:

- `GET /api/products` — fetches all products from PostgreSQL
- `GET /api/products/{id}` — fetches a single product by ID with proper 404 handling

A `GlobalExceptionHandler` translates domain exceptions (`ProductNotFoundException`, `IllegalArgumentException`) into consistent JSON error responses with appropriate HTTP status codes. The application uses Spring Boot's default HikariCP datasource pooling. The database connection details are externalized through Kubernetes configuration:

- `api-config` ConfigMap provides `SPRING_PROFILES_ACTIVE`, `ENVIRONMENT_NAME`, `DB_HOST`, `DB_PORT`, `DB_NAME`, and pool sizing values.
- `api-db-secret` Secret provides `DB_USERNAME` and `DB_PASSWORD`.

Database schema creation and seed data are handled by Flyway migrations in `src/main/resources/db/migration`. The SQL is packaged with the application and runs when the API starts, so PostgreSQL no longer depends on a ConfigMap-mounted init script.

The API tier is deployed as a Kubernetes `Deployment` with 4 replicas and a `RollingUpdate` strategy. It has startup, liveness, and readiness probes backed by Spring Boot Actuator endpoints. The startup probe prevents false restarts during slow JVM startup. Kubernetes recreates pods after deletion, and rolling updates can be demonstrated with `kubectl set image`.

The API is exposed through:

- `catalog-api` ClusterIP Service
- `catalog-api-ingress` GKE Ingress

The database tier is deployed as a PostgreSQL `StatefulSet` with one replica. It uses:

- `postgres` ClusterIP Service for stable internal service discovery
- `postgres-secret` Secret for database name, username, and password
- A `PersistentVolumeClaim` created from `volumeClaimTemplates`
- A `NetworkPolicy` that restricts inbound traffic to only `catalog-api` pods

Pod IPs are not used anywhere. The API connects to PostgreSQL using the stable service DNS name from the selected environment ConfigMap, for example `postgres.nagp-dev.svc.cluster.local`.

## Justification For The Resources Utilized

### API Deployment

The API tier uses a Deployment because it is stateless. Four replicas satisfy the assignment requirement and provide basic high availability during pod deletion and rolling updates. The rolling update configuration uses `maxSurge: 1` and `maxUnavailable: 1`, allowing Kubernetes to replace pods gradually while retaining service availability.

Resource settings:

```yaml
requests:
  cpu: 150m
  memory: 256Mi
limits:
  cpu: 500m
  memory: 512Mi
```

These values are conservative for a lightweight Spring Boot API. Requests let the scheduler place pods predictably, and limits prevent individual pods from consuming excessive node capacity.

### API Probes

The API tier uses three types of probes:

- **Startup probe** (`/actuator/health/liveness`, failureThreshold: 30, period: 5s): Gives the JVM up to ~150 seconds to start before Kubernetes considers the pod failed. This prevents false restarts during slow JVM startup on cold nodes.
- **Readiness probe** (`/actuator/health/readiness`): Routes traffic to the pod only when it is ready to serve requests.
- **Liveness probe** (`/actuator/health/liveness`): Restarts the pod if it becomes unresponsive after startup.

### API HPA

The HPA keeps a minimum of 4 pods and can scale to 8 pods based on observed CPU and memory utilization. This demonstrates resource optimization by adding capacity only when measured demand requires it.

### API Service And Ingress

The API uses an internal ClusterIP Service and a GKE Ingress. This keeps pod identities hidden and stable while still exposing the API externally through the cloud load balancer created by GKE Ingress.

### Database StatefulSet

PostgreSQL uses a StatefulSet because it needs stable identity and persistent storage. The assignment requires one database pod, so the StatefulSet replica count is `1`. The database service remains ClusterIP-only and is not exposed outside the cluster.

Resource settings:

```yaml
requests:
  cpu: 100m
  memory: 256Mi
limits:
  cpu: 500m
  memory: 512Mi
```

### Database NetworkPolicy

A `NetworkPolicy` restricts inbound traffic to the PostgreSQL pod so that only `catalog-api` pods can connect on port 5432. All other pods in the namespace and cluster are denied access. This demonstrates a zero-trust security model and defense-in-depth — even if an attacker gains access to the cluster, they cannot reach the database from arbitrary pods.

### Persistent Storage

The database PVC requests `5Gi` using `standard-rwo`. This is sufficient for the small assignment dataset and demonstrates that data survives pod deletion and rescheduling.

### ConfigMap Usage

The service tier profile, environment name, database location, and pool settings are provided through `api-config`. An environment-specific ConfigMap file exists for `dev` at `k8s/environments/dev/environment.yaml`.

### Secret Usage

Database credentials are stored in Kubernetes Secret objects and consumed through environment variables. An environment-specific Secret exists for `dev` in `k8s/environments/dev/environment.yaml`. The password is not written as clear text in the API deployment or database StatefulSet.

## Self-Healing Demonstration

API self-healing:

```powershell
kubectl delete pod -n nagp-dev -l app=catalog-api
kubectl get pods -n nagp-dev -w
```

The Deployment controller recreates pods until the desired replica count returns to 4.

Database self-healing:

```powershell
kubectl delete pod -n nagp-dev postgres-0
kubectl get pods -n nagp-dev -w
```

The StatefulSet controller recreates `postgres-0` and reattaches the PVC, preserving existing data.

## Persistence Demonstration

After deleting the database pod, call:

```powershell
curl -H "Host: catalog-api-dev.example.com" http://INGRESS_EXTERNAL_IP/api/products
```

The same records should be returned because the PostgreSQL data directory is stored on the PVC.

## FinOps Considerations

At least three cost optimization opportunities are included:

1. Tune API requests and limits from observed `kubectl top pods` metrics instead of over-provisioning.
2. Use HPA to scale out only under measured load and keep the minimum replica count aligned with availability needs.
3. Keep the database as one pod with a small right-sized PVC for the assignment workload.
4. Use namespace `ResourceQuota` to prevent accidental resource growth.
5. Prefer GKE Autopilot for predictable pay-per-pod billing when running small workloads.
6. Define resource requests and limits on the database tier as well, preventing noisy-neighbor interference and enabling bin-packing.

Implemented resource optimization:

- API and database requests and limits are explicitly set.
- HPA uses observed CPU and memory metrics.
- A load generation job is provided so scaling behavior can be demonstrated and measured.
- The README includes a concrete tuning workflow using `kubectl top pods`.
