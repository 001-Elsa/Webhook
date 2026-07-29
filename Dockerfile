FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
COPY notification-platform/pom.xml notification-platform/pom.xml
COPY demo-order-service/pom.xml demo-order-service/pom.xml
COPY receiver-mock/pom.xml receiver-mock/pom.xml
RUN mvn -B -DskipTests dependency:go-offline
COPY . .
ARG MODULE
RUN mvn -B -pl ${MODULE} -am -DskipTests package

FROM eclipse-temurin:17-jre-noble
WORKDIR /app
ARG MODULE
COPY --from=build /workspace/${MODULE}/target/*.jar app.jar
# Upgrade OS packages so Trivy's ignore-unfixed HIGH/CRITICAL gate stays green.
RUN apt-get update \
    && apt-get upgrade -y \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
USER 10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
