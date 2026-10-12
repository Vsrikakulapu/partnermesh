# PartnerMesh

I built PartnerMesh to work through the hard part of microservices: keeping two services in
agreement when requests get duplicated, instances crash and calls between them fail.

It's an enrollment system made of two Spring Boot services. A partner enrolls through the
enrollment service, which then asks a separate provisioning service to set up their account.
Each service has its own PostgreSQL database and they only talk over HTTP. The goal is that no
matter what fails along the way, every enrollment ends up with exactly one account, or is
clearly marked as failed.

Java 21, Spring Boot, PostgreSQL, Docker, Kubernetes, GitHub Actions.

## How it handles failures

Clients send an idempotency key with each request. If the same request comes in twice, because
of a retry or a double click, the second one gets back the original response instead of
creating a second enrollment. This also holds when the duplicates arrive at the exact same
moment, which I tested with 16 simultaneous requests.

The enrollment and the request to provision it are saved together in one database transaction
(the outbox pattern). So if the service crashes right after accepting an enrollment, the
provisioning request isn't lost. A background worker picks it up and sends it.

If the provisioning service is down or slow, the worker retries with exponential backoff and
some randomness, so several instances don't all retry at the same moment. Errors that won't go
away on retry, like a bad request, aren't retried. If retries run out, the enrollment is marked
as failed with the reason.

If an instance dies in the middle of a call, the work it had claimed expires after a while and
another instance takes it over. If the old instance comes back late, its updates are ignored,
so it can't overwrite the newer result.

The trickiest case is when provisioning succeeds but the response never makes it back. The
retry sends the same key, so the provisioning service recognizes it and returns the existing
account instead of creating a second one.

## Running it

```bash
docker compose up -d --build --wait
./scripts/e2e.sh
```

The script tells the provisioning service to reject two calls and lose the response to a third,
sends the same enrollment twice, then checks that it still ends up active with exactly one
account. The provisioning service has a small fault injection endpoint for this, so you can
break it on purpose and watch the system recover.

## Testing

The integration tests run both services' logic against a real PostgreSQL database and cover
each failure case above: duplicate and simultaneous requests, retries, giving up, permanent
errors, lost responses, crashed instances, and stale instances coming back.

Every push to GitHub runs three things: the full test suite, the end-to-end script against the
Docker setup, and the same script against a local Kubernetes cluster where the services run
with two replicas, health checks and autoscaling. Both services also export Prometheus metrics
for request latency, retries and the backlog of pending work.

The repo also has a setup for deploying to Azure Kubernetes Service with a managed PostgreSQL
database, which deploys from GitHub Actions once Azure credentials are added.
