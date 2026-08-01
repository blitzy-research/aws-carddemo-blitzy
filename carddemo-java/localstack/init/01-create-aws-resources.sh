#!/usr/bin/env bash
# Copyright Amazon.com, Inc. or its affiliates.
# All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License").
# You may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
# either express or implied. See the License for the specific
# language governing permissions and limitations under the License
#
# =================================================================================================
# CardDemo - AWS resource bootstrap for the local validation stack
#
# WHAT THIS IS. A ready hook for the LocalStack Community emulator. carddemo-java/docker-compose.yml
# bind-mounts this directory read-only at /etc/localstack/init/ready.d, and the emulator runs every
# executable script it finds there, in lexical order, once the edge port is genuinely serving. The
# numeric filename prefix reserves that ordering; it does not imply further scripts exist. There is
# exactly one script in this directory and it provisions exactly three resources:
#
#   1. a first-in-first-out queue   the single online-to-batch bridge of the legacy estate
#   2. an object-store bucket       batch file staging, with object versioning enabled
#   3. a notification topic         operational fan-out for job completion
#
# The three are independent, so the order below is for reading and is not a dependency order. Every
# one of the eight migration validation gates is designed to run against this stack: no mainframe,
# no staging environment and no real AWS account is involved at any point.
#
# WHY IT MUST BE IDEMPOTENT. A ready hook re-runs on every container start, and the emulator's hook
# runner invokes this file directly and raises if it exits non-zero. "Already there" is therefore a
# normal outcome, is tolerated explicitly on each resource, and no second run appends state. That
# tolerance mirrors the legacy provisioning job stream, which followed each of its six
# generation-data-group definitions with an explicit reset of the already-exists condition.
#
# NO LEGACY SOURCE TEXT APPEARS IN THIS MODULE. No program, job, map, copybook or resource
# definition from the mainframe estate is copied into carddemo-java. Resource names, record widths
# and resource-definition attribute NAMES are cited below as metadata - which is how the queue
# contract is documented without transcribing it. Traceability is by citation:
#
#   source checkout : 7756d895ffeb65f7ea72aaa609e356d9899afcec
#   upstream stamp  : CardDemo_v1.0-15-g27d6c6f-68, dated 2022-07-19
#
# -------------------------------------------------------------------------------------------------
# THE NAMES ARE MANDATED, NOT CHOSEN
#
# src/main/resources/application-local.yml is their authoritative source. The same strings are bound
# byte-identically in application.yml, in the test and production overlays, in docker-compose.yml -
# which passes them into this container - and here, where the resources are created. A disagreement
# has NO fail-fast signal: the legacy queue write was defined errors-ignored, so the publisher logs
# a failure and carries on, and the stack would start cleanly and then come up short on the first
# submission with nothing in the start-up log naming the cause.
#
#   carddemo-batch-staging       object-store bucket
#   JOBS.fifo                    submission queue - the .fifo suffix is REQUIRED, not decoration:
#                                the queue service rejects a first-in-first-out queue whose name
#                                omits it, which is a start-up failure rather than a style choice
#   carddemo-job-notifications   notification topic
#   us-east-1                    region
#
# Correspondence with the property paths the module's configuration types bind, so a reader can see
# what each resource here serves. The paths are the overlays' contract, quoted not restated:
#
#   carddemo.aws.s3.bucket                  -> the bucket created below
#   carddemo.aws.s3.prefix.*                -> one key prefix per output family, declared once in
#                                              application.yml. Key prefixes are not resources and
#                                              no placeholder object is created for them: an empty
#                                              marker key would be returned to a reader listing
#                                              that prefix, and would gain a new version on every
#                                              re-run of this hook
#   carddemo.aws.sqs.job-submission-queue   -> the queue created below
#   carddemo.aws.sqs.message-group-id       -> carddemo-job-submission. One stable group id is what
#                                              preserves append order. It is an attribute the
#                                              publisher puts on each message, so it provisions
#                                              nothing and is named here only to record why the
#                                              queue must be first-in-first-out
#   carddemo.aws.sqs.record-length          -> 80, the fixed width of one submitted card image
#   carddemo.aws.sqs.fail-on-error          -> false, the errors-ignored posture described above
#   carddemo.aws.sns.job-notification-topic -> the topic created below
#
# Region and endpoint are deliberately not in that namespace; they belong to the AWS integration's
# own properties under spring.cloud.aws.
#
# -------------------------------------------------------------------------------------------------
# WHAT IS DELIBERATELY ABSENT - each absence is a decision, not an oversight
#
#   * PUBLISH-ONLY. Nothing in this module reads from the queue or the topic, so no receive path,
#     no secondary failure queue, no access policy, no permission grant and no attached consumer is
#     created for either of them. The legacy definition was output-only.
#   * No retention rule, encryption configuration, object lock or replication on the bucket. Object
#     versioning alone carries the generation semantics; see the bucket section.
#   * No service beyond the object store, the queue and the topic. The emulator is started with
#     exactly those three enabled, and that is the whole AWS surface this module uses.
#   * No auxiliary data store of any kind. The legacy system had none, and adding one would move
#     the very behaviour the performance gate is meant to record.
#   * No timing, volume or capacity figure anywhere in this file, in a setting or in a comment. No
#     numeric service level is documented in the legacy estate, so the performance gate MEASURES a
#     baseline rather than testing against a threshold.
#   * No paid-tier emulator setting and no activation of any kind: the Community edition covers all
#     three services. No sign-in values and no key literals appear in this file - awslocal, shipped
#     inside the container, resolves the edge endpoint, the region and the emulator's throwaway
#     sign-in values internally, which is why no endpoint flag appears on any command below.
#   * No network fetch, download or package install. awslocal is the only tool used.
# =================================================================================================

# Fail fast and fail loudly. The emulator's hook runner raises when this script exits non-zero, so a
# provisioning failure surfaces in the container log instead of leaving a half-built stack that looks
# healthy. -u additionally turns a mistyped variable into an error rather than an empty name. No
# pipeline is used anywhere below, so no further shell option is needed and the script stays valid
# under a strict POSIX shell as well as the bash it declares.
set -eu

# The canonical resource names. These defaults are byte-identical to the ones bound in
# src/main/resources/application.yml, src/main/resources/application-local.yml,
# src/test/resources/application-test.yml and docker-compose.yml. A disagreement produces a stack
# that starts cleanly and then fails on the first publish, with no start-up error to point at it.
# The queue keeps the legacy transient-data resource name, suffixed only because a FIFO queue must
# be - see docs/decision-log.md DL-092.
#
# Each name may be redirected by the environment - docker-compose.yml passes exactly these four
# variables into the container - but the defaults ARE the canonical values above, so an unset
# environment still produces the agreed stack.
REGION="${AWS_DEFAULT_REGION:-us-east-1}"
BUCKET="${CARDDEMO_S3_BUCKET:-carddemo-batch-staging}"
QUEUE="${CARDDEMO_SQS_QUEUE:-JOBS.fifo}"
MESSAGE_GROUP_ID="${CARDDEMO_SQS_MESSAGE_GROUP_ID:-carddemo-job-submission}"
TOPIC="${CARDDEMO_SNS_TOPIC:-carddemo-job-notifications}"

# The bootstrap step has no other instrumentation, and the Gate 5 runbook in carddemo-java/README.md
# reads these lines back out of the emulator's container log. Every line is prefixed so it can be
# found, and every resource reports itself as it becomes ready.
log() { printf '[carddemo-init] %s\n' "$*"; }

log "bootstrap starting: region=${REGION} bucket=${BUCKET} queue=${QUEUE} topic=${TOPIC}"
log "message group id ${MESSAGE_GROUP_ID} is carried per message by the publisher, not provisioned"

# ------------------------------------------------------------------------------------------------
# RESOURCE 1 of 3 - the submission queue
#
# This replaces the estate's sole online-to-batch bridge: one CICS transient-data queue, named
# JOBS, written from exactly one site in one online program. Its resource-definition attributes are
# behavioural, so each is preserved rather than approximated:
#
#   RECORDSIZE(80) with RECORDFORMAT(FIXED)   one card is one message, every body a fixed-width
#                                             eighty-character image, never concatenated
#   BLOCKFORMAT(UNBLOCKED)                    each card is individually addressable, so cards are
#                                             published one at a time and never as a batched blob
#   DISPOSITION(MOD)                          writes append, so ORDER IS PART OF THE CONTRACT. Hence
#                                             a first-in-first-out queue and one stable message
#                                             group: a standard queue would silently reorder
#   ERROROPTION(IGNORE)                       a write failure was tolerated, so the publisher logs
#                                             and continues - which is precisely why this queue must
#                                             pre-exist rather than be created on first use
#   OPENTIME(INITIAL)                         the queue was open before first use, so it is
#                                             provisioned here and never lazily by application code
#   TYPEFILE(OUTPUT)                          publish-only, as recorded above
#
# CONTENT-BASED DEDUPLICATION IS EXPLICITLY OFF, AND THAT IS THE MOST CONSEQUENTIAL LINE IN THIS
# FILE. It is stated rather than left to the service default so that the intent is visible and does
# not get tidied away. A first-in-first-out queue needs either content-based deduplication or an
# explicit per-message deduplication id, and content-based deduplication hashes the message body
# and silently discards a repeat inside its deduplication window. One submitted job image is
# seventeen eighty-character cards, but only FOURTEEN of the seventeen bodies are distinct: three
# cards are the identical comment delimiter and two more are the identical in-stream delimiter. With
# content-based deduplication three cards would be accepted and discarded, seventeen published cards
# would arrive as fourteen, and the failure would surface as an arbitrary-looking message count with
# no diagnostic trail. The publisher therefore supplies its own deduplication id, composed of the
# submission identity and the card ordinal, which keeps the duplicate bodies distinct while still
# collapsing a genuine double-publish of the same submission.
#
# FifoQueue and ContentBasedDeduplication are the only attributes set. Both are contractual; no
# other attribute is configured, so nothing here expresses a service level.
if awslocal sqs get-queue-url --queue-name "${QUEUE}" >/dev/null 2>&1; then
  log "queue ${QUEUE} already present - left as it is"
else
  awslocal sqs create-queue \
    --queue-name "${QUEUE}" \
    --attributes FifoQueue=true,ContentBasedDeduplication=false \
    >/dev/null
  log "created queue ${QUEUE}"
fi

QUEUE_URL="$(awslocal sqs get-queue-url --queue-name "${QUEUE}" --output text)"
QUEUE_ATTRS="$(awslocal sqs get-queue-attributes \
  --queue-url "${QUEUE_URL}" \
  --attribute-names FifoQueue ContentBasedDeduplication \
  --query 'Attributes.[FifoQueue,ContentBasedDeduplication]' \
  --output text)"
log "queue ${QUEUE} ready at ${QUEUE_URL}"
# Read back rather than assumed: this line is the evidence that ordering is guaranteed and that no
# card of a submission can be silently discarded.
log "queue ${QUEUE} attributes [FifoQueue ContentBasedDeduplication] = ${QUEUE_ATTRS}"

# ------------------------------------------------------------------------------------------------
# RESOURCE 2 of 3 - the batch staging bucket
#
# This replaces the sequential output data sets and the generation-data-group bases that retained
# their history - statement text at eighty bytes per record, statement markup at one hundred, the
# transaction report at one hundred and thirty-three, the reject file at four hundred and thirty,
# and the backup output.
#
# OBJECT VERSIONING IS THE GENERATION-DATA-GROUP REPLACEMENT, AND IS APPLIED HERE AND ONLY HERE.
# The bucket is created by this script, so its versioning belongs to this script too; applying it in
# two places risks two conflicting settings. The legacy generation depth was itself inconsistent -
# declared as five for six bases in one job member and as ten for one of them in another - and is
# resolved to ten as the later and more specific declaration; the resolution is recorded in
# docs/decision-log.md rather than restated here. Versioning is what discharges those semantics, and
# it alone: no retention rule is configured.
#
# create-bucket carries no region flag on purpose. awslocal applies the container's own region, and
# passing one would oblige a matching location constraint for any region other than the default.
if awslocal s3api head-bucket --bucket "${BUCKET}" >/dev/null 2>&1; then
  log "bucket ${BUCKET} already present - left as it is"
else
  awslocal s3api create-bucket --bucket "${BUCKET}" >/dev/null
  log "created bucket ${BUCKET}"
fi

# Re-applying the same versioning state is a no-op, so this runs unconditionally and stays idempotent.
awslocal s3api put-bucket-versioning \
  --bucket "${BUCKET}" \
  --versioning-configuration Status=Enabled \
  >/dev/null
BUCKET_VERSIONING="$(awslocal s3api get-bucket-versioning --bucket "${BUCKET}" --query 'Status' --output text)"
log "bucket ${BUCKET} object versioning = ${BUCKET_VERSIONING}"

# ------------------------------------------------------------------------------------------------
# RESOURCE 3 of 3 - the job notification topic
#
# Operational fan-out for job completion. It carries notifications only; no business record travels
# through it, nothing in this module reads from it, and nothing is attached to it.
#
# create-topic is idempotent by definition - given the same name it returns the existing topic - so
# it needs no existence guard and simply reports the identifier either way.
TOPIC_ARN="$(awslocal sns create-topic --name "${TOPIC}" --query 'TopicArn' --output text)"
log "topic ${TOPIC} ready at ${TOPIC_ARN}"

log "AWS resource bootstrap complete: 3 of 3 resources ready (queue, bucket, topic)"
