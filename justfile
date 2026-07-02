# EuroTransit Justfile - Quick commands for the team

set shell := ["bash", "-c"]
services := "catalog orders inventory payments notifications"

# List all available commands (runs by default if you just type 'just')
default:
    @just --list

# Build all services (skipping tests for speed)
build:
    @for svc in {{services}}; do \
        echo "Building $svc"; \
        cd $svc && ./gradlew build -x test && cd ..; \
    done

# Run tests for all services
test:
    @for svc in {{services}}; do \
        echo "Testing $svc"; \
        cd $svc && ./gradlew test && cd ..; \
    done

# Run a specific service locally (e.g., 'just run orders')
run service:
    @echo "Starting {{service}}"
    cd {{service}} && ./gradlew bootRun

# Build the Docker image for a service (e.g., 'just docker payments')
docker service:
    @echo "Building Docker for {{service}}"
    docker build -t eurotransit-{{service}}:local ./{{service}}

# Clean build folders for all projects
clean:
    @for svc in {{services}}; do \
        echo "Cleaning $svc"; \
        cd $svc && ./gradlew clean && cd ..; \
    done