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

FROM eclipse-temurin:17-jre
WORKDIR /app
ARG MODULE
COPY --from=build /workspace/${MODULE}/target/*.jar app.jar
USER 10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
