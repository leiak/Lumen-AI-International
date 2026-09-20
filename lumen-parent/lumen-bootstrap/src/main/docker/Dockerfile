# syntax=docker/dockerfile:1.6
# =====================================================================
# Lumen Bootstrap - multi-stage Dockerfile
# Build context is `lumen-parent/` (parent of the docker/ directory).
# The docker-compose file sets `build.context: ../../../../` from
# inside docker/, so the COPY paths below are written relative to
# `lumen-parent/` (the multi-module Maven tree).
# =====================================================================

# ---------- Stage 1: build ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src

# 1) Copy only the POMs first. Docker will cache this layer until any
#    pom.xml changes, so dependency resolution (mvn dependency:go-offline)
#    is reused across source-only rebuilds.
COPY pom.xml /src/pom.xml
COPY lumen-bootstrap/pom.xml /src/lumen-bootstrap/pom.xml
COPY lumen-common/pom.xml    /src/lumen-common/pom.xml
COPY lumen-rbac/pom.xml      /src/lumen-rbac/pom.xml
COPY lumen-masterdata/pom.xml /src/lumen-masterdata/pom.xml
COPY lumen-numbering/pom.xml /src/lumen-numbering/pom.xml
COPY lumen-notification/pom.xml /src/lumen-notification/pom.xml
COPY lumen-extension/pom.xml   /src/lumen-extension/pom.xml

# Pre-resolve dependencies against the offline cache. `-am` builds the
# module dependency graph for lumen-bootstrap, including any inter-module
# artifacts produced by sibling modules.
RUN mvn -B -pl lumen-bootstrap -am dependency:go-offline

# 2) Now copy the actual sources. Changes here invalidate only the
#    downstream compile/package layers, not the dependency cache.
COPY . /src

# Build the runnable Spring Boot jar (skip tests; CI runs them elsewhere).
RUN mvn -B -pl lumen-bootstrap -am clean package -DskipTests

# ---------- Stage 2: runtime ----------
FROM eclipse-temurin:17-jre
WORKDIR /app

# Create a non-root user for runtime hardening.
RUN groupadd -r lumen && useradd -r -g lumen -d /app -s /sbin/nologin lumen

# Copy the boot jar produced in stage 1 and rename for clarity.
COPY --from=build /src/lumen-bootstrap/target/lumen-bootstrap-*.jar /app/app.jar

# Container-aware JVM sizing (75% of container RAM, not host RAM).
# Plus a sane UTF-8 default + container-optimised GC.
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"

# Default timezone matches the MySQL container and the project timezone.
ENV TZ=Asia/Shanghai

# Default Spring profile; can be overridden at run time.
ENV SPRING_PROFILES_ACTIVE=dev

EXPOSE 8080

USER lumen
ENTRYPOINT ["java", "-jar", "/app/app.jar"]