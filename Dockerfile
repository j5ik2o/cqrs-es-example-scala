# syntax=docker/dockerfile:1
FROM eclipse-temurin:17-jdk-jammy AS build

ARG SBT_VERSION=1.13.1

RUN apt-get update \
  && apt-get install -y --no-install-recommends curl ca-certificates \
  && rm -rf /var/lib/apt/lists/* \
  && curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" \
    | tar -xz -C /opt

WORKDIR /build
COPY . .
RUN /opt/sbt/bin/sbt -batch bootstrap/assembly

FROM eclipse-temurin:17-jre-jammy

WORKDIR /app
COPY --from=build /build/modules/bootstrap/target/scala-3.6.4/cqrs-es-example-scala.jar /app/cqrs-es-example-scala.jar

ENTRYPOINT ["java", "-cp", "/app/cqrs-es-example-scala.jar"]
