# syntax=docker/dockerfile:1.7
# Build from the REPO ROOT (the service needs the parent pom and the common module):
#   docker build -f notification-service/Dockerfile -t flash-sale/notification-service:local .

# ---- build stage: full JDK + Maven; never shipped ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY common common
# Maven reads EVERY module listed in the parent pom, even with -pl, so the other services' poms must exist
# (their sources are not needed and stay out of this image's build).
COPY booking-service/pom.xml booking-service/pom.xml
COPY payment-service/pom.xml payment-service/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml
COPY notification-service notification-service
# The cache mount keeps ~/.m2 between builds, so only the first build downloads dependencies.
# Tests run in CI (mvn verify), not here: the image build must be fast and must not need Docker-in-Docker.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl notification-service -am -DskipTests -Djacoco.skip=true package \
 && cp notification-service/target/notification-service-*.jar /src/app.jar

# ---- runtime stage: JRE only, non-root ----
FROM eclipse-temurin:21-jre-alpine
# Fixed numeric uid: Kubernetes' runAsNonRoot check needs a number, not a name.
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app app
WORKDIR /app
COPY --from=build --chown=app:app /src/app.jar /app/app.jar
USER 10001:10001
EXPOSE 8083
# Size the heap from the container's memory limit instead of a fixed -Xmx, and die on OOM so the orchestrator restarts us.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
# 'exec' makes java PID 1, so SIGTERM reaches Spring and graceful shutdown works.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
