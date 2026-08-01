#!/usr/bin/env bash
# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
#
# LocalStack ready.d bootstrap for the CardDemo Java module.
#
# Creates the three AWS resources the migrated estate needs:
#   * S3 bucket        - batch file staging; replaces the sequential datasets and the six GDG
#                        bases (LIMIT resolved to 10) via object versioning.
#   * SQS FIFO queue   - replaces EXEC CICS WRITEQ TD QUEUE('JOBS').
#                        CSD attributes preserved: RECORDSIZE(80) RECORDFORMAT(FIXED) -> one
#                        80-character card per message; DISPOSITION(MOD) -> message-group
#                        ordering; ERROROPTION(IGNORE) -> publisher treats failure as non-fatal.
#   * SNS topic        - job completion / operational notification fan-out.
#
# Runs automatically inside the LocalStack container once the edge port is serving. It is
# idempotent, so re-running it (or restarting the container) is safe.

set -euo pipefail

# The canonical resource names. These defaults are byte-identical to the ones bound in
# src/main/resources/application.yml, src/main/resources/application-local.yml,
# src/test/resources/application-test.yml and docker-compose.yml. A disagreement produces a stack
# that starts cleanly and then fails on the first publish, with no start-up error to point at it.
REGION="${AWS_DEFAULT_REGION:-us-east-1}"
BUCKET="${CARDDEMO_S3_BUCKET:-carddemo-batch-staging}"
QUEUE="${CARDDEMO_SQS_QUEUE:-carddemo-jobs.fifo}"
TOPIC="${CARDDEMO_SNS_TOPIC:-carddemo-job-notifications}"

log() { printf '[carddemo-init] %s\n' "$*"; }

log "region=${REGION} bucket=${BUCKET} queue=${QUEUE} topic=${TOPIC}"

# --------------------------------------------------------------------------- S3
if awslocal s3api head-bucket --bucket "${BUCKET}" >/dev/null 2>&1; then
  log "S3 bucket ${BUCKET} already exists"
else
  awslocal s3api create-bucket --bucket "${BUCKET}" --region "${REGION}" >/dev/null
  log "created S3 bucket ${BUCKET}"
fi

# GDG generations become object versions; retention is expressed as versioning, not LIMIT(n).
awslocal s3api put-bucket-versioning \
  --bucket "${BUCKET}" \
  --versioning-configuration Status=Enabled >/dev/null
log "enabled versioning on ${BUCKET}"

# Logical prefixes mirroring the legacy output datasets.
for prefix in statements/ statements-html/ reports/ rejects/ backups/ inbound/; do
  awslocal s3api put-object --bucket "${BUCKET}" --key "${prefix}" >/dev/null
done
log "seeded staging prefixes: statements/ statements-html/ reports/ rejects/ backups/ inbound/"

# -------------------------------------------------------------------------- SQS
if awslocal sqs get-queue-url --queue-name "${QUEUE}" >/dev/null 2>&1; then
  log "SQS queue ${QUEUE} already exists"
else
  # Content-based deduplication is deliberately OFF. The seventeen cards of one job image are not
  # all distinct: two pairs of cards carry identical eighty-character bodies. With content-based
  # deduplication the second card of each pair would be accepted and silently discarded, and the
  # submitted job would arrive short. The publisher therefore supplies an explicit deduplication
  # identifier on every message, derived from the submission identity plus the card ordinal, which
  # keeps duplicate bodies distinct while still suppressing a genuine retry of the same card.
  awslocal sqs create-queue \
    --queue-name "${QUEUE}" \
    --attributes FifoQueue=true,ContentBasedDeduplication=false,VisibilityTimeout=60,MessageRetentionPeriod=345600 \
    >/dev/null
  log "created SQS FIFO queue ${QUEUE} (explicit per-message deduplication ids)"
fi
QUEUE_URL="$(awslocal sqs get-queue-url --queue-name "${QUEUE}" --output text)"
log "queue url ${QUEUE_URL}"

# -------------------------------------------------------------------------- SNS
TOPIC_ARN="$(awslocal sns create-topic --name "${TOPIC}" --output text --query 'TopicArn')"
log "SNS topic ready ${TOPIC_ARN}"

log "AWS resource bootstrap complete"
