.PHONY: billing-job

# Manual trigger for the daily billing job -- the same ApiApplication `--job=billing-run`
# entry point used by the Kubernetes CronJob (k8s/billing-job-cronjob.yaml) and proven
# by BillingJobEntryPointIT. Requires local infra (docker-compose up) to already be
# running. `-am install` first, since `spring-boot:run` targets a single module (`-pl
# api` alone, no `-am`) -- with `-am` Maven invokes the `run` goal for every reactor
# module it also builds, not just api, and fails on the ones with no main class.
billing-job:
	cd backend && mvn -q -pl api -am install -DskipTests
	cd backend && mvn -q -pl api spring-boot:run -Dspring-boot.run.arguments=--job=billing-run
