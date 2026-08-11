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
# A ready hook for the LocalStack Community emulator. carddemo-java/docker-compose.yml bind-mounts
# this directory read-only at /etc/localstack/init/ready.d and the emulator runs every executable
# script it finds there once the edge port is serving. This is the only script in the directory and
# it provisions exactly three independent resources - a first-in-first-out queue (the single
# online-to-batch bridge of the legacy estate), an object-store bucket with versioning enabled for
# batch file staging, and a notification topic for job completion. The validation gates that need an
# AWS surface run against this stack - end-to-end boundary verification, the performance baseline, the
# named-artefact run, interface contract verification and the scope-coverage tier - with no mainframe,
# no staging environment and no real AWS account. The remaining gates are build-time and static: the
# zero-warning build, the unsafe-code audit and the integration sign-off need nothing from here.
#
# IT MUST BE IDEMPOTENT. A ready hook re-runs on every container start and the hook runner raises if
# this file exits non-zero, so "already there" is a normal outcome, is tolerated per resource, and no
# second run appends state - which mirrors the legacy provisioning stream, where each
# generation-data-group definition was followed by an explicit reset of the already-exists condition.
#
# THE NAMES ARE MANDATED, NOT CHOSEN. src/main/resources/application.yml is their authoritative
# source - it is the one document that declares all five, and the local overlay states outright that it
# inherits the region rather than restating it - and the same strings are bound byte-identically in the
# local, test and production overlays, in docker-compose.yml, which passes them into this container, and
# here, where the resources are created. A disagreement has no fail-fast signal: the legacy queue write
# was defined errors-ignored, so the stack would start cleanly and come up short at the first submission
# with nothing in the start-up log naming the cause.
#
# THE KEY PATHS BELOW ARE THE ONES THE APPLICATION BINDS, and a test holds this table to them. Two of
# them named nothing until this checkpoint: the settings type renamed the bucket key and the queue key,
# every overlay followed, and this table did not. The superseded spellings are deliberately NOT repeated
# here - they are recorded in docs/decision-log.md - because a plausible key path written anywhere in
# this repository is a path a reader will grep for, and finding it in a comment is what made the stale
# table convincing in the first place. The two current spellings are one line further down each.
#
# The values were right throughout, so nothing failed and nothing could - a comment binds no property -
# which is exactly why the table is now asserted rather than maintained by hand: an operator reading it
# to find out which variable to set was being sent to a key no profile declares.
#
#   carddemo-batch-staging       object-store bucket        -> carddemo.aws.s3.batch-staging-bucket
#   JOBS.fifo                    submission queue           -> carddemo.aws.sqs.job-queue
#   carddemo-job-submission      message group id           -> carddemo.aws.sqs.message-group-id
#   carddemo-job-notifications   notification topic         -> carddemo.aws.sns.job-notification-topic
#   us-east-1                    region                     -> carddemo.aws.region
#
# Two further keys live under the same prefix and are deliberately absent from the table, because this
# script provisions no resource for either: carddemo.aws.endpoint-override, which only the local and
# test overlays declare and which aims the clients at this emulator, and
# carddemo.aws.expected-account-id, which production requires so that the trust check can refuse a
# queue, topic or bucket outside the account the deployment declares it owns. See
# docs/decision-log.md DL-302 and DL-314.
#
# The .fifo suffix is required rather than decoration: the queue service refuses a first-in-first-out
# queue whose name omits it. The message group id provisions nothing - the publisher puts it on each
# message, and one stable value is what preserves append order. Nor is an object key a resource: the
# job that writes an object composes its key, so no marker object is created for any staging area - an
# empty marker would be returned to a reader listing that key prefix and would gain a version on every
# re-run.
#
# TWO FACTS ABOUT THIS QUEUE ARE DELIBERATELY NOT PROPERTIES: the fixed eighty-character record width
# and the ignore-on-error failure tolerance. Both come from the legacy queue definition and neither
# is a deployment choice - a different width is a different record format and a different tolerance is
# a different failure contract - so each lives where it is enforced. The width is
# JobSubmissionException.RECORD_SIZE, which JobSubmissionService checks every card's encoded byte
# count against before publishing; the tolerance is the publisher's own structure, which catches a
# failed write, reports it through the return value and never rethrows. See docs/decision-log.md
# DL-094.
#
# EACH RESOURCE IS ALSO GIVEN AN ACCESS POSTURE, AND THE POSTURE IS PART OF THE CONTRACT. Naming a
# resource and owning it says nothing about who else can reach it, and every one of the three carries
# data that must not be reachable: the bucket holds statements, reports and rejected records bearing
# account identifiers, card numbers and balances; the queue carries the eighty-column job-control cards
# a scheduler acts on; the topic carries completion notices. So the bucket is given all four
# public-access controls, a policy, and default encryption; and the queue and the topic are each given a
# policy. Every one of those is read back and compared, exactly as the queue's two behavioural
# attributes are, and the production trust check refuses to start a deployment whose resources lack the
# same five properties - which is why they are provisioned here rather than only described. See
# docs/decision-log.md DL-346.
#
# THE POLICIES GRANT NOTHING AND REFUSE ONE THING. Each attached policy denies every principal every
# action of its own service when aws:SecureTransport is false, and nothing else. The topic additionally
# keeps a grant, because the notification service attaches one to every topic it creates and setting a
# policy replaces it: rather than silently narrowing the service's own access model, the grant is
# reproduced in a least-privilege form - the two actions this module actually performs, confined by a
# condition on the owning account - and the denial is added beside it. The bucket and the queue are
# created with no policy at all and are given no grant, because this module's own access to them is
# granted by the identity it runs as, not by the resource.
#
# A DENIAL OF PLAIN TRANSPORT DOES NOT BREAK THIS STACK, which is the obvious objection to it: the
# emulator is reached over http. It does not enforce resource policies - identity and resource
# authorisation are off unless the emulator is asked for them - so the documents are stored, read back
# and judged here while every call still succeeds. That is exactly the property this hook needs: the
# same posture a production account is required to carry can be provisioned and verified locally
# without a certificate, a proxy or a second profile.
#
# DELIBERATELY ABSENT, each an explicit decision: publish-only, so no receive path, secondary failure
# queue, permission grant or consumer is created - the legacy definition was output-only; no retention
# rule, object lock or replication on the bucket, because versioning alone carries the generation
# semantics; no service beyond the three; no auxiliary data store, because the legacy system had none
# and adding one would move the behaviour the performance gate records; no timing, volume or capacity
# figure anywhere, because no numeric service level is documented in the legacy estate; no paid-tier
# setting, no sign-in value or key literal - awslocal resolves the edge endpoint, the region and the
# emulator's throwaway credentials internally, which is why no endpoint flag appears on any command; and
# no network fetch or package install.
#
# Traceability is by citation: source checkout 7756d895ffeb65f7ea72aaa609e356d9899afcec, upstream
# stamp CardDemo_v1.0-15-g27d6c6f-68 dated 2022-07-19. No legacy program, job, map, copybook or
# resource-definition text is copied into this module.
# =================================================================================================

# Fail fast and fail loudly: the hook runner raises when this script exits non-zero, so a
# provisioning failure surfaces in the container log instead of leaving a half-built stack that looks
# healthy, and -u turns a mistyped variable into an error rather than an empty name. No further option
# is set, so this stays valid under a strict POSIX shell; pipefail is absent because it is not POSIX
# and there is no pipeline in this file at all.
set -eu

# Diagnostics are funnelled through one place because the bootstrap has no other instrumentation: the
# Gate 5 runbook in carddemo-java/README.md reads these lines back out of the container log, so every
# line carries the prefix and every resource reports itself once verified.
#
# EVERY EMITTED LINE IS SANITISED FIRST, which is a correctness control rather than tidiness. The five
# names are read from the environment and everything else logged here comes back from the emulator, so
# an unescaped value could decide how many records appear in the log: one line feed is enough for the
# remainder of a value to arrive as a further line indistinguishable from one this script wrote - up
# to and including the completion line a gate reviewer greps for. Deleting the C0 controls and DEL and
# bounding the length keeps one call to log equal to exactly one record.
LOG_PREFIX='[carddemo-init]'

# Long enough for the longest legitimate line - a resource identifier with its label - and short
# enough that a pathological value cannot flood the container log. It bounds a diagnostic and
# expresses no service level.
MAX_LOGGED_LENGTH=512

# Removes every byte that could end or reposition a log record. It walks the value with parameter
# expansion and calls nothing: this is a container ready hook whose guard is exercised with PATH
# pointing at an empty directory, and an unreachable tr would return nothing, so every value would
# differ from itself, every record would lose its body and the sanitiser would fail unsafely. A
# built-in cannot be made unreachable. Nothing legitimate is altered, which is what lets the
# validation below use this as its control-byte test.
strip_control() {
  _sc_remaining="$*"
  _sc_kept=''
  while [ -n "${_sc_remaining}" ]; do
    _sc_tail="${_sc_remaining#?}"
    _sc_head="${_sc_remaining%"${_sc_tail}"}"
    _sc_remaining="${_sc_tail}"
    case "${_sc_head}" in
      [[:cntrl:]]) continue ;;
    esac
    _sc_kept="${_sc_kept}${_sc_head}"
  done
  printf '%s' "${_sc_kept}"
}

# Bounds an already-sanitised record, with built-ins for the same reason: cut is no more reachable
# than tr, and a clamp that quietly empties every record is worse than none.
bound_length() {
  _bl_remaining="$1"
  _bl_kept=''
  while [ -n "${_bl_remaining}" ] && [ "${#_bl_kept}" -lt "${MAX_LOGGED_LENGTH}" ]; do
    _bl_tail="${_bl_remaining#?}"
    _bl_kept="${_bl_kept}${_bl_remaining%"${_bl_tail}"}"
    _bl_remaining="${_bl_tail}"
  done
  printf '%s' "${_bl_kept}"
}

log() {
  printf '%s %s\n' "${LOG_PREFIX}" "$(bound_length "$(strip_control "$*")")"
}

fail() {
  printf '%s FAILED: %s\n' "${LOG_PREFIX}" \
    "$(bound_length "$(strip_control "$*")")" >&2
  exit 1
}

# Refuses an empty value, a value carrying a control byte, and a value longer than the service
# accepts. A name falls back to its canonical default when the environment leaves it unset, and an
# identifier a service returns is expected to name a resource, so neither has a legitimate empty form.
require_bounded_value() {
  if [ -z "$2" ]; then
    fail "$1 is empty. A name falls back to its canonical default when the environment leaves it" \
      'unset or blank, and an identifier a service returns is expected to name a resource, so' \
      'neither has a legitimate empty form.'
  fi
  if [ "$(strip_control "$2")" != "$2" ]; then
    fail "$1 holds a control character. A carriage return or a line feed in a name would forge" \
      'further records in this log, so such a value is refused rather than escaped.'
  fi
  if [ "${#2}" -gt "$3" ]; then
    fail "$1 is longer than the $3 characters the service accepts: $2"
  fi
}

# The five names, each defaulting to its canonical value so the stack comes up with no environment at
# all, and each overridable for a parallel stack.
REGION="${AWS_DEFAULT_REGION:-us-east-1}"
BUCKET="${CARDDEMO_S3_BUCKET:-carddemo-batch-staging}"
QUEUE="${CARDDEMO_SQS_QUEUE:-JOBS.fifo}"
MESSAGE_GROUP_ID="${CARDDEMO_SQS_MESSAGE_GROUP_ID:-carddemo-job-submission}"
TOPIC="${CARDDEMO_SNS_TOPIC:-carddemo-job-notifications}"

# The first gate every name passes, before it reaches a command or the log. A name outside
# [A-Za-z0-9._-] is refused rather than escaped, and its value is deliberately not repeated in the
# refusal, so a hostile value cannot reach the log through the message that rejects it.
require_safe_name() {
  case "$2" in
    '')
      printf '[carddemo-init] FATAL: %s resolved to an empty name; it is required to provision the local stack.\n' \
        "$1" >&2
      exit 1
      ;;
    *[!A-Za-z0-9._-]*)
      printf '[carddemo-init] FATAL: %s holds a character outside [A-Za-z0-9._-]; it names an AWS resource and is written to this log, so it is refused before either happens. Correct the environment variable that supplies it; its value is deliberately not repeated here.\n' \
        "$1" >&2
      exit 1
      ;;
  esac
}

require_safe_name AWS_DEFAULT_REGION "${REGION}"
require_safe_name CARDDEMO_S3_BUCKET "${BUCKET}"
require_safe_name CARDDEMO_SQS_QUEUE "${QUEUE}"
require_safe_name CARDDEMO_SQS_MESSAGE_GROUP_ID "${MESSAGE_GROUP_ID}"
require_safe_name CARDDEMO_SNS_TOPIC "${TOPIC}"

require_bounded_value 'the region' "${REGION}" 32
case "${REGION}" in
  *[!a-z0-9-]*)
    fail 'the region holds a character outside the lower-case letters, digits and hyphens a' \
      "region name is made of: ${REGION}"
    ;;
esac

require_bounded_value 'the bucket name' "${BUCKET}" 63
case "${BUCKET}" in
  *[!a-z0-9.-]*)
    fail 'the bucket name holds a character outside the lower-case letters, digits, dots and' \
      "hyphens the object store accepts: ${BUCKET}"
    ;;
esac
if [ "${#BUCKET}" -lt 3 ]; then
  fail "the bucket name is shorter than the three characters the object store accepts: ${BUCKET}"
fi

require_bounded_value 'the queue name' "${QUEUE}" 80
case "${QUEUE}" in
  *[!A-Za-z0-9._-]*)
    fail 'the queue name holds a character outside the letters, digits, hyphens, underscores and' \
      "the .fifo suffix a queue name is made of: ${QUEUE}"
    ;;
esac
case "${QUEUE}" in
  *.fifo) ;;
  *)
    fail "the queue name ${QUEUE} does not end in .fifo. The queue service will not create a" \
      'first-in-first-out queue without that suffix, and a standard queue would silently reorder' \
      'the cards of a submission.'
    ;;
esac

require_bounded_value 'the message group id' "${MESSAGE_GROUP_ID}" 128
case "${MESSAGE_GROUP_ID}" in
  *[!A-Za-z0-9._-]*)
    fail 'the message group id holds a character outside the letters, digits, dots, hyphens and' \
      "underscores this stack uses: ${MESSAGE_GROUP_ID}"
    ;;
esac

require_bounded_value 'the topic name' "${TOPIC}" 256
case "${TOPIC}" in
  *[!A-Za-z0-9_-]*)
    fail 'the topic name holds a character outside the letters, digits, hyphens and underscores' \
      "the notification service accepts: ${TOPIC}"
    ;;
esac

# The statement every attached policy carries: every principal, every action of the one service, denied
# when the call did not arrive over an encrypted transport. Composed here once so the three resources
# cannot drift into three different postures, and matched by exactly the rule the production trust check
# applies - AwsResourcePolicyRules, which requires the wildcard principal, the service's own action
# wildcard and the boolean condition together, so a denial narrowed to one action or one principal does
# not satisfy it.
transport_denial_statement() {
  printf '{"Sid":"DenyInsecureTransport","Effect":"Deny","Principal":"*","Action":"%s:*","Resource":%s,"Condition":{"Bool":{"aws:SecureTransport":"false"}}}' \
    "$1" "$2"
}

# The queue service's attribute flag cannot carry a policy in its shorthand Key=Value form: the
# document's own quoting ends the value and the tool reports a parse error before any call is made. The
# attribute map is therefore passed as JSON, in which the document is a JSON *string* and every quote
# inside it is escaped. Written out rather than escaped by a helper because this file has no pipeline and
# a character-walking escaper would be more code than the single document it would escape.
queue_policy_attributes() {
  printf '{"Policy":"{\\"Version\\":\\"2012-10-17\\",\\"Statement\\":[{\\"Sid\\":\\"DenyInsecureTransport\\",\\"Effect\\":\\"Deny\\",\\"Principal\\":\\"*\\",\\"Action\\":\\"sqs:*\\",\\"Resource\\":\\"%s\\",\\"Condition\\":{\\"Bool\\":{\\"aws:SecureTransport\\":\\"false\\"}}}]}"}' \
    "$1"
}

# Holds a policy read back out of a service to the property it was attached for. Both substrings are
# required: a document that denies nothing and one that never names the transport condition are
# different failures, and neither is the posture this stack claims.
require_transport_denial() {
  case "$2" in
    *Deny*) ;;
    *)
      fail "$1 reports a policy that denies nothing after a denial was attached. The attached" \
        'document is deliberately not repeated here; re-run this hook against a stack the tool can' \
        'write policies to.'
      ;;
  esac
  case "$2" in
    *aws:SecureTransport*)
      : ;;
    *)
      fail "$1 reports a policy that does not name the secure-transport condition. Without it every" \
        'call to this resource is served whether or not it arrived encrypted, and the production trust' \
        'check refuses to start against a resource in that state.'
      ;;
  esac
}

# The opening record. Everything above this line is validation, so a stack that fails to provision
# says so before it claims to have started.
log "bootstrap starting: region=${REGION} bucket=${BUCKET} queue=${QUEUE} topic=${TOPIC}"
log "message group id ${MESSAGE_GROUP_ID} is carried per message by the publisher, not provisioned"

# awslocal ships inside the emulator image, so its absence almost always means this file is being run
# on the host rather than as a ready hook.
if ! command -v awslocal >/dev/null 2>&1; then
  fail 'awslocal was not found on PATH. It ships inside the LocalStack image, so this almost' \
    'always means the script is running somewhere other than inside the emulator container. It' \
    'is a ready hook, not a host script: bring the stack up with docker compose up -d from' \
    'carddemo-java, which mounts localstack/init read-only at /etc/localstack/init/ready.d and' \
    'runs it there.'
fi

# RESOURCE 1 - the submission queue. Created first-in-first-out with content-based deduplication OFF,
# and both attributes are then read back rather than assumed. FifoQueue is fixed at creation and
# cannot be altered, so an existing standard queue is reported as unfixable in place instead of being
# silently accepted. Content-based deduplication must be false because three of the seventeen cards of
# a submission share a body with another card: a deduplicating queue would accept seventeen and
# deliver fourteen. The publisher supplies its own deduplication identifier per card instead.
if awslocal sqs get-queue-url --queue-name "${QUEUE}" >/dev/null 2>&1; then
  log "queue ${QUEUE} already present - verifying its attributes"
else
  awslocal sqs create-queue \
    --queue-name "${QUEUE}" \
    --attributes FifoQueue=true,ContentBasedDeduplication=false \
    >/dev/null
  log "created queue ${QUEUE}"
fi

QUEUE_URL="$(awslocal sqs get-queue-url --queue-name "${QUEUE}" --output text)"
require_bounded_value 'the queue url returned by the queue service' "${QUEUE_URL}" 512
case "${QUEUE_URL}" in
  *"/${QUEUE}") ;;
  *)
    fail "the queue service returned ${QUEUE_URL} for queue ${QUEUE}, which does not name that" \
      'queue. Nothing further is provisioned, because every check below would be reading a' \
      'different resource than the one under contract.'
    ;;
esac

queue_attribute() {
  awslocal sqs get-queue-attributes \
    --queue-url "${QUEUE_URL}" \
    --attribute-names "$1" \
    --query "Attributes.$1" \
    --output text
}

QUEUE_IS_FIFO="$(queue_attribute FifoQueue)"
if [ "${QUEUE_IS_FIFO}" != 'true' ]; then
  fail "queue ${QUEUE} reports FifoQueue=${QUEUE_IS_FIFO}, and it must be true. That attribute" \
    'is fixed at creation and cannot be altered, so the existing queue cannot be corrected in' \
    'place: delete it and let this hook create it again. Until then append order is not' \
    'guaranteed and the cards of one submission can arrive out of sequence.'
fi

QUEUE_CONTENT_DEDUP="$(queue_attribute ContentBasedDeduplication)"
if [ "${QUEUE_CONTENT_DEDUP}" != 'false' ]; then
  log "queue ${QUEUE} reports ContentBasedDeduplication=${QUEUE_CONTENT_DEDUP}" \
    '- setting it to false'
  awslocal sqs set-queue-attributes \
    --queue-url "${QUEUE_URL}" \
    --attributes ContentBasedDeduplication=false \
    >/dev/null
  QUEUE_CONTENT_DEDUP="$(queue_attribute ContentBasedDeduplication)"
  if [ "${QUEUE_CONTENT_DEDUP}" != 'false' ]; then
    fail "queue ${QUEUE} still reports ContentBasedDeduplication=${QUEUE_CONTENT_DEDUP} after" \
      'being set to false. Three of the seventeen cards of a submission share a body with another' \
      'card, so a deduplicating queue would accept seventeen and deliver fourteen.'
  fi
fi

log "queue ${QUEUE} verified at ${QUEUE_URL}"
log "queue ${QUEUE} attributes verified: FifoQueue=${QUEUE_IS_FIFO}" \
  "ContentBasedDeduplication=${QUEUE_CONTENT_DEDUP}"

# The queue's access posture. Its own identifier is read first, because a policy names the resource it
# governs and a document naming a different queue would be attached without complaint. The attribute is
# written outside the existence guard, so a queue that already existed without a policy acquires one on
# the next run rather than keeping whatever it had.
QUEUE_ARN="$(queue_attribute QueueArn)"
require_bounded_value 'the queue identifier returned by the queue service' "${QUEUE_ARN}" 512
case "${QUEUE_ARN}" in
  arn:aws:sqs:*:*:"${QUEUE}") ;;
  *)
    fail "the queue service reported ${QUEUE_ARN} as the identifier of queue ${QUEUE}, which does" \
      'not name that queue. No policy is attached, because it would govern a resource other than the' \
      'one under contract.'
    ;;
esac
awslocal sqs set-queue-attributes \
  --queue-url "${QUEUE_URL}" \
  --attributes "$(queue_policy_attributes "${QUEUE_ARN}")" \
  >/dev/null
QUEUE_POLICY="$(queue_attribute Policy)"
require_transport_denial "queue ${QUEUE}" "${QUEUE_POLICY}"
log "queue ${QUEUE} policy verified: every sqs action denied over plain transport, nothing granted"

# RESOURCE 2 - the staging bucket. Object versioning is enabled and then read back, because
# versioning is what carries the generation semantics of the retained legacy output data sets: without
# it each write would replace its predecessor irrecoverably.
if awslocal s3api head-bucket --bucket "${BUCKET}" >/dev/null 2>&1; then
  log "bucket ${BUCKET} already present - verifying its object versioning"
else
  awslocal s3api create-bucket --bucket "${BUCKET}" >/dev/null
  log "created bucket ${BUCKET}"
fi

awslocal s3api put-bucket-versioning \
  --bucket "${BUCKET}" \
  --versioning-configuration Status=Enabled \
  >/dev/null
BUCKET_VERSIONING="$(awslocal s3api get-bucket-versioning \
  --bucket "${BUCKET}" \
  --query 'Status' \
  --output text)"
if [ "${BUCKET_VERSIONING}" != 'Enabled' ]; then
  fail "bucket ${BUCKET} reports object versioning ${BUCKET_VERSIONING}, and it must be Enabled." \
    'Versioning is what carries the generation semantics of the retained output data sets, so' \
    'without it each write would replace its predecessor irrecoverably.'
fi
log "bucket ${BUCKET} object versioning verified: ${BUCKET_VERSIONING}"

# The bucket's access posture, in the order the production trust check establishes it: every public
# route closed, then the policy that governs the rest, then the algorithm applied to an object written
# without naming one. All three are applied outside the existence guard, so a bucket that already
# existed unhardened is hardened on the next run.
awslocal s3api put-public-access-block \
  --bucket "${BUCKET}" \
  --public-access-block-configuration \
  'BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true' \
  >/dev/null
# Read back one control per call, for the same reason the queue's attributes are: four values in one
# response are separated by a control byte this script deletes before it logs anything, and a comparison
# against the wrong control is exactly the mistake a single combined read would hide. All four are
# required - two govern access-control lists and two govern policies, so three of four leaves one route
# to the objects open.
for PUBLIC_ACCESS_CONTROL in \
  BlockPublicAcls IgnorePublicAcls BlockPublicPolicy RestrictPublicBuckets; do
  PUBLIC_ACCESS_STATE="$(awslocal s3api get-public-access-block \
    --bucket "${BUCKET}" \
    --query "PublicAccessBlockConfiguration.${PUBLIC_ACCESS_CONTROL}" \
    --output text)"
  if [ "${PUBLIC_ACCESS_STATE}" != 'True' ]; then
    fail "bucket ${BUCKET} reports ${PUBLIC_ACCESS_CONTROL}=${PUBLIC_ACCESS_STATE}, and it must be" \
      'set. Each of the four controls closes a different route to the statements, reports and rejected' \
      'records written here, so one unset control is one open route.'
  fi
done
log "bucket ${BUCKET} public access verified: all 4 controls set"

BUCKET_POLICY="{\"Version\":\"2012-10-17\",\"Statement\":[$(transport_denial_statement s3 \
  "[\"arn:aws:s3:::${BUCKET}\",\"arn:aws:s3:::${BUCKET}/*\"]")]}"
awslocal s3api put-bucket-policy --bucket "${BUCKET}" --policy "${BUCKET_POLICY}" >/dev/null
BUCKET_POLICY_ATTACHED="$(awslocal s3api get-bucket-policy \
  --bucket "${BUCKET}" \
  --query 'Policy' \
  --output text)"
require_transport_denial "bucket ${BUCKET}" "${BUCKET_POLICY_ATTACHED}"
log "bucket ${BUCKET} policy verified: every s3 action denied over plain transport, nothing granted"

# The bucket's default is the whole of the at-rest protection, because no writer in this module names an
# algorithm per object. The managed algorithm is chosen rather than a customer-managed key: a key would
# be an account resource this hook has no business creating, and the property being provisioned is that
# an unnamed algorithm is still an algorithm.
awslocal s3api put-bucket-encryption \
  --bucket "${BUCKET}" \
  --server-side-encryption-configuration \
  '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}' \
  >/dev/null
BUCKET_ENCRYPTION="$(awslocal s3api get-bucket-encryption \
  --bucket "${BUCKET}" \
  --query 'ServerSideEncryptionConfiguration.Rules[0].ApplyServerSideEncryptionByDefault.SSEAlgorithm' \
  --output text)"
if [ "${BUCKET_ENCRYPTION}" != 'AES256' ]; then
  fail "bucket ${BUCKET} reports default encryption ${BUCKET_ENCRYPTION}, and it must be AES256." \
    'No writer in this module names an algorithm per object, so the bucket default is the whole of the' \
    'at-rest protection for every statement, report and rejected record written here.'
fi
log "bucket ${BUCKET} default encryption verified: ${BUCKET_ENCRYPTION}"

# RESOURCE 3 - the notification topic. Creating a topic that already exists is idempotent in the
# service itself, so no existence probe is needed; the returned identifier is checked to name this
# topic and is then resolved back through the service.
TOPIC_ARN="$(awslocal sns create-topic --name "${TOPIC}" --query 'TopicArn' --output text)"
require_bounded_value 'the topic identifier returned by the notification service' "${TOPIC_ARN}" 512
case "${TOPIC_ARN}" in
  arn:aws:sns:*:*:"${TOPIC}") ;;
  *)
    fail "the notification service returned ${TOPIC_ARN} for topic ${TOPIC}, which is not a topic" \
      'identifier naming that topic.'
    ;;
esac
RESOLVED_TOPIC_ARN="$(awslocal sns get-topic-attributes \
  --topic-arn "${TOPIC_ARN}" \
  --query 'Attributes.TopicArn' \
  --output text)"
if [ "${RESOLVED_TOPIC_ARN}" != "${TOPIC_ARN}" ]; then
  fail "topic ${TOPIC} was created as ${TOPIC_ARN} but resolves to ${RESOLVED_TOPIC_ARN}."
fi
log "topic ${TOPIC} verified at ${TOPIC_ARN}"

# The topic's access posture. Setting a policy replaces the one the notification service attached when
# it created the topic, so the grant is reproduced rather than dropped - narrowed to the two actions this
# module performs and confined by the same condition on the owning account the service's own default
# uses. The owning account is read from the service instead of being derived from the identifier, so a
# mistake in either would be a mismatch rather than a policy naming an account nobody checked.
TOPIC_OWNER="$(awslocal sns get-topic-attributes \
  --topic-arn "${TOPIC_ARN}" \
  --query 'Attributes.Owner' \
  --output text)"
case "${TOPIC_OWNER}" in
  '' | *[!0-9]*)
    fail "the notification service reported ${TOPIC_OWNER} as the account owning topic ${TOPIC}," \
      'which is not an account number. No policy is attached, because the grant it carries is confined' \
      'to that account and a policy confined to a non-account would grant nothing or everything.'
    ;;
esac
TOPIC_POLICY="{\"Version\":\"2012-10-17\",\"Statement\":\
[{\"Sid\":\"AllowOwningAccountToPublish\",\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"*\"},\
\"Action\":[\"sns:Publish\",\"sns:GetTopicAttributes\"],\"Resource\":\"${TOPIC_ARN}\",\
\"Condition\":{\"StringEquals\":{\"AWS:SourceOwner\":\"${TOPIC_OWNER}\"}}},\
$(transport_denial_statement sns "\"${TOPIC_ARN}\"")]}"
awslocal sns set-topic-attributes \
  --topic-arn "${TOPIC_ARN}" \
  --attribute-name Policy \
  --attribute-value "${TOPIC_POLICY}" \
  >/dev/null
TOPIC_POLICY_ATTACHED="$(awslocal sns get-topic-attributes \
  --topic-arn "${TOPIC_ARN}" \
  --query 'Attributes.Policy' \
  --output text)"
require_transport_denial "topic ${TOPIC}" "${TOPIC_POLICY_ATTACHED}"
log "topic ${TOPIC} policy verified: publish confined to the owning account, every sns action denied" \
  'over plain transport'

# The completion record the gate runbook reads. It claims verification rather than mere readiness
# because every read-back above ends the script instead of warning.
log 'AWS resource bootstrap complete: 3 of 3 resources verified (queue, bucket, topic)'
