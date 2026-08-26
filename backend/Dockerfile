# Builds the runnable application image. One image, two entry paths: the default
# command starts the web server; a Kubernetes CronJob (k8s/billing-job-cronjob.yaml)
# or an operator overrides the command with `--job=billing-run` to run the same image
# as a one-shot billing job instead (see ApiApplication#main).

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml .
COPY audit/pom.xml audit/
COPY billing-core/pom.xml billing-core/
COPY billing-job/pom.xml billing-job/
COPY dunning/pom.xml dunning/
COPY invoicing/pom.xml invoicing/
COPY notifications/pom.xml notifications/
COPY api/pom.xml api/
RUN mvn -B -q -pl api -am dependency:go-offline

COPY audit/src audit/src
COPY billing-core/src billing-core/src
COPY billing-job/src billing-job/src
COPY dunning/src dunning/src
COPY invoicing/src invoicing/src
COPY notifications/src notifications/src
COPY api/src api/src
# The reactor's test-jar execution also drops an `api-*-tests.jar` alongside the
# executable jar in api/target/ -- rename explicitly rather than glob, so the runtime
# stage's COPY below can't accidentally match both.
RUN mvn -B -q -pl api -am package -DskipTests \
    && find api/target -maxdepth 1 -name 'api-*.jar' ! -name '*-tests.jar' -exec mv {} api/target/app.jar \;

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S billing && adduser -S billing -G billing
WORKDIR /app
COPY --from=build /workspace/api/target/app.jar app.jar
USER billing

ENTRYPOINT ["java", "-jar", "app.jar"]
