# One Dockerfile for both services:  docker build --build-arg SERVICE=enrollment-service .
FROM maven:3.9-eclipse-temurin-21 AS build
ARG SERVICE
WORKDIR /src
COPY pom.xml .
COPY enrollment-service/pom.xml enrollment-service/
COPY provisioning-service/pom.xml provisioning-service/
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl ${SERVICE} -am dependency:resolve || true
COPY enrollment-service/src enrollment-service/src
COPY provisioning-service/src provisioning-service/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl ${SERVICE} -am package -DskipTests \
    && cp ${SERVICE}/target/${SERVICE}.jar /app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home app
COPY --from=build /app.jar /app/app.jar
USER 10001
EXPOSE 8080 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
