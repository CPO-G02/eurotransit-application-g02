# EuroTransit Application

Repository layout:

- `backend/` contains the backend microservices.
- `frontend/` contains the static frontend.
- `docs/` contains application-side project notes and AI logs.
- `tools/` contains local helper scripts.

Backend services:

- `backend/catalog`
- `backend/inventory`
- `backend/notifications`
- `backend/orders`
- `backend/payments`
- `backend/payment-gateway-sim`

Quick commands:

- `just build` builds all backend services without tests.
- `just test` runs tests for all backend services.
- `just run <service>` starts one backend service locally.
- `just docker <service>` builds one backend service Docker image.
