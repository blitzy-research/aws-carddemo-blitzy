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
#   carddemo.aws.sns.job-notification-topic -> the topic created below
#
# Region and endpoint are deliberately not in that namespace; they belong to the AWS integration's
# own properties under spring.cloud.aws.
#
# TWO FACTS ABOUT THIS QUEUE ARE DELIBERATELY NOT PROPERTIES, which is why the list above does not
# carry them: the fixed eighty-character record width, and the ignore-on-error failure tolerance.
# Both come from the legacy queue definition and neither is a deployment choice - a different width
# is a different record format and a different tolerance is a different failure contract - so
# neither describes anything a deployment may set. An earlier revision did declare them, as
# carddemo.aws.sqs.record-length and carddemo.aws.sqs.fail-on-error; nothing in the application
# bound either one, so they read as configuration while being inert, and they have been withdrawn.
# Both live where they are actually enforced, and a test asserts that neither key has come back:
#
#   the width      JobSubmissionException.RECORD_SIZE - the constant JobSubmissionService checks
#                  every card's ENCODED BYTE count against before publishing. A card of any other
#                  width is refused outright rather than trimmed or padded, because its trailing
#                  spaces are part of the record
#   the tolerance  the publisher's own structure - the write is caught, reported through the return
#                  value and never rethrown, which is what the ignore-on-error attribute recorded
#                  below becomes once there is no transient-data queue to ignore an error on
#
# See docs/decision-log.md DL-094.
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
# healthy. -u additionally turns a mistyped variable into an error rather than an empty name.
#
# No further shell option is set, so the script stays valid under a strict POSIX shell as well as
# the bash it declares. In particular pipefail is deliberately absent - it is not POSIX - and
# nothing here needs it: THERE IS NO PIPELINE IN THIS FILE AT ALL. The sanitiser below is written
# with shell built-ins rather than with tr and cut, so it cannot be disarmed by an environment in
# which those tools are unreachable - and a container ready hook is exactly the place where PATH is
# minimal. Every command whose result the script acts on is run on its own, where -e already covers
# it.
set -eu

# -------------------------------------------------------------------------------------------------
# DIAGNOSTICS, AND WHY EVERY LINE IS FUNNELLED THROUGH ONE PLACE
#
# The bootstrap step has no other instrumentation, and the Gate 5 runbook in carddemo-java/README.md
# reads these lines back out of the emulator's container log. Every line is prefixed so it can be
# found, and every resource reports itself once it has been verified.
#
# EVERY LINE THIS SCRIPT EMITS IS SANITISED FIRST, and that is a correctness control rather than
# tidiness. Every one of the five names below is read from the environment, and everything else
# logged here comes back from the emulator, so an unescaped value would let its own content decide
# how many records appear in the log. A single line feed is enough: the value is echoed into the
# opening line, the reader treats the feed as a record boundary, and whatever follows it arrives as
# a further line indistinguishable from one this script wrote - up to and including the completion
# line, which is precisely the evidence a gate reviewer greps for. Deleting the C0 control bytes and
# DEL, and bounding the length, keeps one call to log equal to exactly one record.
LOG_PREFIX='[carddemo-init]'

# Long enough for the longest legitimate line here - a resource identifier with its label - and
# short enough that a pathological value cannot flood the container log. It bounds a diagnostic, and
# expresses no service level.
MAX_LOGGED_LENGTH=512

# Remove every byte that could end or reposition a log record - the C0 controls and DEL. Nothing
# legitimate is altered: no permitted name, URL or identifier holds a control byte, so a valid value
# reads back unchanged - which is what lets the validation below use this as its control-byte test.
#
# IT WALKS THE VALUE WITH PARAMETER EXPANSION AND CALLS NOTHING. tr -d reads more clearly and is the
# obvious way to write this, but this file is a container ready hook and its guard is exercised with
# PATH pointing at an empty directory: an unreachable tr returns nothing, so every value differs from
# itself and is refused, and every emitted record loses its body - a sanitiser that fails safe still
# fails, and it takes the diagnostic with it. A built-in cannot be made unreachable. The loop is
# bounded by the length of its argument, and every argument is one name or one line of text.
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

# Bound an already-sanitised record to the length recorded above, built-ins for the same reason: cut
# is no more reachable than tr, and a clamp that quietly empties every record is worse than none.
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

# One record, one line, whatever was passed in.
log() {
  printf '%s %s\n' "${LOG_PREFIX}" "$(bound_length "$(strip_control "$*")")"
}

# The one way this script stops. The message goes to standard error and the non-zero exit is what
# the emulator's hook runner raises on, so a refusal is a visible start-up failure rather than a
# note in a log nobody reads. It sanitises for the same reason log does.
fail() {
  printf '%s FAILED: %s\n' "${LOG_PREFIX}" \
    "$(bound_length "$(strip_control "$*")")" >&2
  exit 1
}

# -------------------------------------------------------------------------------------------------
# VALUE VALIDATION
#
# Each name is checked before it is used and before it is logged, against the character set the
# service that owns it accepts. The allowlists are narrower than those services permit, and that is
# deliberate: this script provisions one known stack, so a value outside the canonical shape is far
# more likely to be a mistake - or a control byte aimed at the log - than a legitimate override.
#
# require_bounded_value carries the checks every one of them shares, and the same three checks are
# applied further down to each identifier the services hand back, since a reply is no more trusted
# than an environment value. The control-byte test is the sanitiser itself: if removing control
# bytes changes the value, it held a byte that has no place in a resource name, and it is refused
# rather than escaped. Each name then gets its OWN literal character test rather than a shared
# pattern, because a bracket expression held in a variable is treated as a pattern by some shells
# and as text by others, and this file must read the same way under both.
#
# $1 the label used in diagnostics, $2 the value, $3 the greatest length the service accepts.
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

# -------------------------------------------------------------------------------------------------
# VALIDATE EVERY NAME BEFORE THE FIRST LOG LINE, NOT AFTER IT.
#
# All five names above may be redirected by the environment, and every one of them is interpolated
# into the lines this script emits. Because those lines ARE the evidence Gate 5 reads back out of the
# container log, a name carrying a newline would not merely look untidy: it would append an attacker
# chosen line to that evidence, and the most valuable line to forge is the completion record at the
# end of this file, which an operator reads as proof that all three resources exist. A value such as
#   CARDDEMO_S3_BUCKET='x
#   [carddemo-init] AWS resource bootstrap complete: 3 of 3 resources verified (queue, bucket, topic)'
# would produce exactly that record from a run that provisioned nothing.
#
# The ordering is the whole point of placing this block here. Validating after the opening log line
# would leave that line - which interpolates four of the five names - permanently unguarded, and it
# is the earliest and least scrutinised line in the file. Nothing is emitted before this loop
# completes, so there is no window in which an unchecked name can reach the log.
#
# The pattern is deliberately narrower than any service's own rules and is not a union of them: it is
# the set every legitimate name in this stack is drawn from, so a character outside it is refused
# before it can be printed, whichever service the name was bound for. It is NOT a claim that a name
# passing here is acceptable to all three services - the object store refuses upper case and an
# underscore, and the notification service refuses the dot - which is what the per-service checks
# below it are for, and why they are kept. Only the FIFO suffix's dot is admitted beyond the
# alphanumeric-hyphen-underscore set, because the queue name contractually ends in '.fifo'. A
# rejected name is reported by variable name only. The value is never echoed - echoing it is the very
# thing being prevented, and the variable name is what an operator needs to correct the environment.
# The check takes the label and the value as two arguments rather than reading the variable
# indirectly. Indirect expansion would need either bash's ${!var}, which would falsify this file's
# own claim to run under a strict POSIX shell, or an eval, which is both unnecessary here and the one
# construct a reviewer of a bootstrap script should never have to think about. Two arguments avoid
# both, keep the pattern written exactly once, and leave each call site a single readable line.
require_safe_name() {
  case "$2" in
    '')
      # UNREACHABLE THROUGH THE ENVIRONMENT AS THIS FILE STANDS, AND PRESERVED DELIBERATELY. Each of
      # the five assignments above uses ${VAR:-default}, and the colon form substitutes the default
      # when the variable is empty as well as when it is unset, so an exported empty value arrives
      # here as the canonical default and never as an empty string. The branch is kept because that
      # is a property of the assignment operator rather than of this check: changing any ${VAR:-...}
      # to ${VAR-...}, which is a one-character edit and a plausible one, makes an empty name
      # reachable immediately. A guard that only holds while five unrelated lines keep a particular
      # spelling is not a guard, so the case is retained and its reachability documented instead.
      printf '[carddemo-init] FATAL: %s resolved to an empty name; it is required to provision the local stack.\n' \
        "$1" >&2
      exit 1
      ;;
    *[!A-Za-z0-9._-]*)
      # Matches any character outside the accepted set, which includes every control character, so a
      # newline, a carriage return and a NUL are all refused here. The offending value is withheld.
      # The diagnostic is deliberately one line: a fix for log forging that emitted a multi-line
      # rejection would be reintroducing on the failure path exactly what it prevents on the success
      # path, and the failure path is the one an attacker can reach on demand.
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

# THE TWO LAYERS BELOW ARE BOTH LOAD-BEARING, AND THIS IS THE ORDER THEY MUST RUN IN. Every one of
# the five names has now passed the printing guard above, so none of them holds a control byte or any
# other character outside [A-Za-z0-9._-]. What follows is the per-service layer: the greatest length
# each service accepts, the narrower character set each one actually allows, and the two shape rules
# the object store and the queue impose. That layer names the offending value in its diagnostic,
# which is what a merely wrong name needs and is safe only because anything able to forge a record
# was already refused above. require_bounded_value keeps its own control-byte test regardless: the
# same three checks are applied further down to every identifier the services hand back, and a reply
# passes through no guard above.
# A region name is lower-case letters, digits and hyphens.
require_bounded_value 'the region' "${REGION}" 32
case "${REGION}" in
  *[!a-z0-9-]*)
    fail 'the region holds a character outside the lower-case letters, digits and hyphens a' \
      "region name is made of: ${REGION}"
    ;;
esac

# An object-store bucket name is lower-case letters, digits, dots and hyphens, and at least three
# characters long. Upper case and underscores are rejected by the service itself, so a name carrying
# them would fail on creation rather than here - which is exactly why it is caught here instead.
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

# A queue name is letters, digits, hyphens and underscores, and this one additionally MUST end in
# .fifo - the only dot it may carry. That is not a style check: the queue service refuses to make a
# first-in-first-out queue whose name omits the suffix, so a name without it cannot produce the
# ordering the append-disposition contract below depends on. Refusing it here names the cause.
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

# The message group id provisions nothing - the publisher puts it on each message - but it is
# reported below, so it is held to the same standard as the names that do.
require_bounded_value 'the message group id' "${MESSAGE_GROUP_ID}" 128
case "${MESSAGE_GROUP_ID}" in
  *[!A-Za-z0-9._-]*)
    fail 'the message group id holds a character outside the letters, digits, dots, hyphens and' \
      "underscores this stack uses: ${MESSAGE_GROUP_ID}"
    ;;
esac

# A notification topic name is letters, digits, hyphens and underscores.
require_bounded_value 'the topic name' "${TOPIC}" 256
case "${TOPIC}" in
  *[!A-Za-z0-9_-]*)
    fail 'the topic name holds a character outside the letters, digits, hyphens and underscores' \
      "the notification service accepts: ${TOPIC}"
    ;;
esac

log "bootstrap starting: region=${REGION} bucket=${BUCKET} queue=${QUEUE} topic=${TOPIC}"
log "message group id ${MESSAGE_GROUP_ID} is carried per message by the publisher, not provisioned"

# -------------------------------------------------------------------------------------------------
# THE ONE EXTERNAL DEPENDENCY, CHECKED BEFORE ANY RESOURCE IS TOUCHED OR REPORTED
#
# awslocal is the only tool this script calls. It ships inside the emulator image and resolves the
# edge endpoint, the region and the emulator's throwaway sign-in values internally, which is why no
# endpoint flag and no credential appears on any command below.
#
# Its presence is checked here rather than discovered by a failing call, because the first call
# below is an existence probe whose standard error is discarded on purpose. A missing-command
# message would be swallowed there and an absent tool would read as an absent queue - the script
# would then try to create every resource and fail confusingly on the way. One explicit check turns
# that into a single legible refusal.
#
# It runs after the two opening records and before the first resource call. Both placements refuse
# before anything is created, and this one additionally leaves the log saying what the run set out to
# provision: those records name no resource and report no outcome, so they claim nothing the missing
# tool then fails to deliver, and an operator reading the refusal can see which names were in force
# when it happened. Moving it above them would trade that away for nothing.
if ! command -v awslocal >/dev/null 2>&1; then
  fail 'awslocal was not found on PATH. It ships inside the LocalStack image, so this almost' \
    'always means the script is running somewhere other than inside the emulator container. It' \
    'is a ready hook, not a host script: bring the stack up with docker compose up -d from' \
    'carddemo-java, which mounts localstack/init read-only at /etc/localstack/init/ready.d and' \
    'runs it there.'
fi

# ------------------------------------------------------------------------------------------------
# RESOURCE 1 of 3 - the submission queue
#
# This replaces the estate's sole online-to-batch bridge: one CICS transient-data queue, named
# JOBS, written from exactly one site in one online program. Its declared behaviour is preserved
# rather than approximated:
#
#   fixed-width 80-character records  one card is one message, every body an eighty-character image,
#                                     never concatenated
#   one message per card              each card is individually addressable, so cards are published
#                                     one at a time and never as a batched blob
#   writes append                     so ORDER IS PART OF THE CONTRACT. Hence a first-in-first-out
#                                     queue and one stable message group: a standard queue would
#                                     silently reorder
#   a write failure was tolerated     the publisher logs and continues - which is precisely why this
#                                     queue must pre-exist rather than be created on first use
#   the queue was open before use     so it is provisioned here and never lazily by application code
#   publish only                      as recorded above
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
#
# AN EXISTING QUEUE IS VERIFIED, NOT ASSUMED. "Already present" is a normal outcome on every
# restart, but a queue that is present is not necessarily the queue this module needs: it may
# predate a change to these attributes, or have been created by hand, or by something else entirely
# that happened to choose the same name. Reporting it as ready on the strength of its name alone
# would let a standard queue, or a content-deduplicating one, pass as correct - and both of those
# failures are invisible until a submission arrives short or out of order. So each attribute is read
# back and compared with the exact value required, the one attribute that can be changed is changed,
# and the one that cannot ends the script.
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
# The last segment of the url is the queue name, so this both rejects a malformed reply and confirms
# the reply is about the queue that was asked for.
case "${QUEUE_URL}" in
  *"/${QUEUE}") ;;
  *)
    fail "the queue service returned ${QUEUE_URL} for queue ${QUEUE}, which does not name that" \
      'queue. Nothing further is provisioned, because every check below would be reading a' \
      'different resource than the one under contract.'
    ;;
esac

# One attribute at a time, so that each comparison is against a single unambiguous value rather than
# a positional pair a reader has to line up by eye.
queue_attribute() {
  awslocal sqs get-queue-attributes \
    --queue-url "${QUEUE_URL}" \
    --attribute-names "$1" \
    --query "Attributes.$1" \
    --output text
}

# FifoQueue is fixed when the queue is created and cannot be changed afterwards, so a mismatch is
# not something this script can repair - it can only refuse to report a stack that will silently
# reorder a submission. A standard queue does not carry the attribute at all and the read-back is
# the absent-value marker, which fails this comparison exactly as a literal false would.
QUEUE_IS_FIFO="$(queue_attribute FifoQueue)"
if [ "${QUEUE_IS_FIFO}" != 'true' ]; then
  fail "queue ${QUEUE} reports FifoQueue=${QUEUE_IS_FIFO}, and it must be true. That attribute" \
    'is fixed at creation and cannot be altered, so the existing queue cannot be corrected in' \
    'place: delete it and let this hook create it again. Until then append order is not' \
    'guaranteed and the cards of one submission can arrive out of sequence.'
fi

# ContentBasedDeduplication is mutable, so a wrong value IS repaired here rather than reported: the
# attribute is set and read back again, and only a value that survives that round trip is accepted.
# Setting it is a no-op when it is already false, so the normal restart path performs no write.
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
# Compared rather than merely printed: these two lines are the evidence that ordering is guaranteed
# and that no card of a submission can be silently discarded.
log "queue ${QUEUE} attributes verified: FifoQueue=${QUEUE_IS_FIFO}" \
  "ContentBasedDeduplication=${QUEUE_CONTENT_DEDUP}"

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
  log "bucket ${BUCKET} already present - verifying its object versioning"
else
  awslocal s3api create-bucket --bucket "${BUCKET}" >/dev/null
  log "created bucket ${BUCKET}"
fi

# Re-applying the same versioning state is a no-op, so this runs unconditionally and stays idempotent.
awslocal s3api put-bucket-versioning \
  --bucket "${BUCKET}" \
  --versioning-configuration Status=Enabled \
  >/dev/null
# Read back and COMPARED, because the request above succeeding is not the same fact as versioning
# being on. A bucket whose versioning was suspended reports Suspended, and one that never had it
# reports the absent-value marker; either would leave the generation semantics unimplemented while
# this script reported the bucket ready, and every overwritten statement or report would lose its
# predecessor with nothing to say so.
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

# ------------------------------------------------------------------------------------------------
# RESOURCE 3 of 3 - the job notification topic
#
# Operational fan-out for job completion. It carries notifications only; no business record travels
# through it, nothing in this module reads from it, and nothing is attached to it.
#
# create-topic is idempotent by definition - given the same name it returns the existing topic - so
# it needs no existence guard and simply reports the identifier either way.
#
# The returned identifier is CHECKED rather than echoed. It is the only thing the create call gives
# back, so if it is malformed, empty or names some other topic then nothing else here would notice:
# the topic would be reported ready and the notification path would publish into a name that does
# not exist. Two checks settle it - the identifier has the shape of a topic identifier and its last
# segment is the topic that was asked for, and the topic it names resolves back to the same
# identifier when the service is asked about it.
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

# Reached only when all three resources exist AND every read-back above matched the value required,
# because each comparison ends the script rather than warning. This line is therefore a statement
# that the stack is correct, and not merely that the commands ran - which is what makes it usable as
# gate evidence.
log 'AWS resource bootstrap complete: 3 of 3 resources verified (queue, bucket, topic)'
