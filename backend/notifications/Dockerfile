# build stage
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

# copy gradle config
COPY gradlew .
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts ./

# copy source code
COPY src ./src

# build executable jar skipping tests
RUN chmod +x ./gradlew
RUN ./gradlew bootJar --no-daemon -x test

# runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# copy compiled jar
COPY --from=builder /app/build/libs/*-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]