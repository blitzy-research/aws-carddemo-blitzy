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

REGION="${AWS_DEFAULT_REGION:-us-east-1}"
BUCKET="${CARDDEMO_S3_BUCKET:-carddemo-batch-staging}"
QUEUE="${CARDDEMO_SQS_QUEUE:-JOBS.fifo}"
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
  awslocal sqs create-queue \
    --queue-name "${QUEUE}" \
    --attributes FifoQueue=true,ContentBasedDeduplication=true,VisibilityTimeout=60,MessageRetentionPeriod=345600 \
    >/dev/null
  log "created SQS FIFO queue ${QUEUE} (content-based deduplication enabled)"
fi
QUEUE_URL="$(awslocal sqs get-queue-url --queue-name "${QUEUE}" --output text)"
log "queue url ${QUEUE_URL}"

# -------------------------------------------------------------------------- SNS
TOPIC_ARN="$(awslocal sns create-topic --name "${TOPIC}" --output text --query 'TopicArn')"
log "SNS topic ready ${TOPIC_ARN}"

log "AWS resource bootstrap complete"
