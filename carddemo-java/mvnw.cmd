<# : batch portion
@REM ----------------------------------------------------------------------------
@REM Copyright Amazon.com, Inc. or its affiliates.
@REM All Rights Reserved.
@REM
@REM Licensed under the Apache License, Version 2.0 (the "License").
@REM You may not use this file except in compliance with the License.
@REM You may obtain a copy of the License at
@REM
@REM    http://www.apache.org/licenses/LICENSE-2.0
@REM
@REM Unless required by applicable law or agreed to in writing,
@REM software distributed under the License is distributed on an
@REM "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
@REM either express or implied. See the License for the specific
@REM language governing permissions and limitations under the License
@REM ----------------------------------------------------------------------------
@REM
@REM The grant above is the CardDemo project header. Every member of the legacy
@REM estate this module migrates carries it verbatim, and reproducing it here is
@REM what keeps provenance intact across the migration. The grant immediately
@REM below is the Apache Software Foundation's own, retained because the
@REM executable body of this script is ASF-derived. Both grants are the Apache
@REM License, Version 2.0, so carrying the two side by side is complete
@REM attribution, not a conflict: the CardDemo copyright covers this file as a
@REM project artefact, the ASF notice covers the upstream work it embeds.
@REM
@REM ----------------------------------------------------------------------------
@REM Licensed to the Apache Software Foundation (ASF) under one
@REM or more contributor license agreements.  See the NOTICE file
@REM distributed with this work for additional information
@REM regarding copyright ownership.  The ASF licenses this file
@REM to you under the Apache License, Version 2.0 (the
@REM "License"); you may not use this file except in compliance
@REM with the License.  You may obtain a copy of the License at
@REM
@REM    http://www.apache.org/licenses/LICENSE-2.0
@REM
@REM Unless required by applicable law or agreed to in writing,
@REM software distributed under the License is distributed on an
@REM "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
@REM KIND, either express or implied.  See the License for the
@REM specific language governing permissions and limitations
@REM under the License.
@REM ----------------------------------------------------------------------------
@REM ----------------------------------------------------------------------------
@REM Apache Maven Wrapper startup batch script, version 3.3.4, hardened
@REM
@REM Optional ENV vars
@REM   MVNW_REPOURL - repo url base for downloading maven distribution; it has to
@REM                  be an https URL and must not embed credentials
@REM   MVNW_USERNAME/MVNW_PASSWORD - user and password for downloading maven; they
@REM                  are offered only to the host the distribution URL names
@REM   MVNW_VERBOSE - true: enable verbose log; others: silence the output
@REM   MVNW_LOCK_TIMEOUT_SECONDS - how long to wait for a concurrent invocation to
@REM                  finish installing the distribution before failing; default 300
@REM   MAVEN_USER_HOME - Maven user home; the resolved distribution is cached
@REM                     under %MAVEN_USER_HOME%\wrapper\dists (default ~/.m2)
@REM   MAVEN_OPTS - JVM options for the Maven process; forwarded untouched
@REM   MAVEN_ARGS - default command line arguments; forwarded untouched
@REM   JAVA_HOME - the JDK the Maven process runs on; forwarded untouched
@REM
@REM Only the MVNW_* variables are consumed by this launcher, and the batch
@REM portion clears exactly MVNW_VERBOSE, MVNW_USERNAME, MVNW_PASSWORD and
@REM MVNW_REPOURL before handing control over, so JAVA_HOME, MAVEN_OPTS and
@REM MAVEN_ARGS reach the real mvn launcher unmodified and behave there exactly as
@REM they do for a locally installed Maven. That pass-through is what lets the CI
@REM workflow export MAVEN_ARGS once and have every mvnw.cmd invocation honour it.
@REM ----------------------------------------------------------------------------

@REM ----------------------------------------------------------------------------
@REM CardDemo :: carddemo-java :: Windows Maven Wrapper launcher
@REM ----------------------------------------------------------------------------
@REM Purpose. This script is the mechanism by which a clean checkout of this
@REM repository builds with no preinstalled Maven: a JDK is the only prerequisite
@REM for the build itself, and Windows PowerShell is the only prerequisite for
@REM this launcher - see "Host requirement" below. It resolves, downloads,
@REM verifies, caches and then executes the exact Apache Maven distribution this
@REM module is pinned to, and forwards every argument to it unmodified. Running
@REM "mvnw.cmd clean verify" from the carddemo-java directory therefore behaves
@REM exactly as "./mvnw clean verify" does on Linux or macOS, which is what makes
@REM the zero-warning deployable-artifact requirement reproducible rather than
@REM machine-dependent. This file and mvnw are deliberately behaviourally
@REM equivalent: they read the same properties file, honour the same ENV vars,
@REM enforce the same transport and digest contract, validate the cache the same
@REM way and resolve the same pinned Maven, so one README section describes both
@REM without qualification.
@REM
@REM Provenance. The executable body derives from the canonical Apache Maven
@REM Wrapper Windows launcher, version 3.3.4, taken from the only-script
@REM distribution published on Maven Central as
@REM   org.apache.maven.wrapper:maven-wrapper-distribution:3.3.4:zip:only-script
@REM whose mvnw.cmd member has SHA-256
@REM   46eedb8419bd14fe70d5bb2916d7b6f51806e51b39d5b76a42610384ca929c1c
@REM so the resolution, download, checksum and dispatch logic can be re-verified
@REM against upstream at any time. The upstream body is the starting point rather
@REM than the delivered content: the divergences below are deliberate and each one
@REM fixes a defect that upstream carries.
@REM
@REM Divergence from upstream, stated exhaustively so a diff against 3.3.4 is
@REM explainable. Presentational: the licence header, these comment blocks, the
@REM ENV vars documented above and the wrapperVersion lookup with its verbose
@REM logging. Behavioural, each marked inline with "CardDemo modification":
@REM
@REM   1. Paths are passed, never pasted. cmd.exe cannot hand arguments to the
@REM      PowerShell half of this polyglot, so the two paths that half needs - the
@REM      directory this script lives in and this script's own full path - have to
@REM      cross the boundary somehow. Upstream crosses it by pasting %~dp0 and
@REM      %~f0 into single-quoted PowerShell string literals inside the dispatch
@REM      line. An apostrophe is a legal character in a Windows path, and one
@REM      appearing in a checkout path closes the literal early: at best the
@REM      launcher dies on a parse error in a valid working directory, at worst a
@REM      crafted path closes the literal and the remainder is parsed as
@REM      PowerShell and executed inside the generated script block. Both paths
@REM      are published as environment values instead and read back through $env:,
@REM      so no path text is ever parsed as source.
@REM
@REM   2. The caller's environment is left alone, and nothing crosses the boundary
@REM      as CMD source. Upstream runs without SETLOCAL, so it permanently clears
@REM      the caller's MVNW_USERNAME and MVNW_PASSWORD - an authenticated retry in
@REM      the same shell then behaves differently from the first attempt - and
@REM      leaves its own private variables behind. It also writes every assignment
@REM      unquoted, so an ampersand, a pipe or a redirection character in a valid
@REM      path, in PSModulePath, or in the resolved command is re-parsed by
@REM      cmd.exe as a command separator. Here the whole batch portion runs inside
@REM      SETLOCAL, every assignment is written in the quoted SET "name=value"
@REM      form, the private variables are reset unconditionally rather than
@REM      trusted when pre-set, and only the numeric exit code crosses back out
@REM      through ENDLOCAL & EXIT /B.
@REM
@REM   3. Delayed expansion is disabled explicitly. Upstream never disables it, so
@REM      under a shell started with cmd /V:ON an exclamation mark anywhere in the
@REM      checkout path, in an argument or in an environment value is expanded or
@REM      stripped after %~dp0, %~f0, %* and the FOR substitutions have been
@REM      performed. SETLOCAL DisableDelayedExpansion makes the literal reading
@REM      unconditional regardless of how the calling shell was started.
@REM
@REM   4. The PowerShell dependency is declared and located, not assumed. Upstream
@REM      invokes whatever "powershell" PATH happens to resolve, and reports only
@REM      "Cannot start maven from wrapper" when that is not an interpreter. This
@REM      launcher prefers the absolute Windows PowerShell path under %SystemRoot%,
@REM      accepts the Sysnative view of it for a 32-bit shell on 64-bit Windows,
@REM      falls back to pwsh.exe and then to powershell.exe on PATH, and fails
@REM      early with a diagnostic that names the dependency when none is found.
@REM
@REM   5. distributionSha256Sum is MANDATORY, not optional, and the archive shape
@REM      and transport are checked before anything is fetched. Upstream verifies
@REM      only when the key happens to be set, and its URL test accepts any name
@REM      whose extension can be stripped - so foo.zip passes a check that
@REM      advertises *-bin.zip. Here an absent or malformed digest is a hard
@REM      failure, the final URL component must be an apache-maven -bin.zip, and
@REM      both the configured URL and the MVNW_REPOURL rewrite of it must be https
@REM      and free of embedded credentials. Every diagnostic that prints a URL
@REM      prints it redacted.
@REM
@REM   6. The download follows its own redirects, one hop at a time. Upstream uses
@REM      System.Net.WebClient, which follows redirects automatically and answers
@REM      an authentication challenge from whatever host the chain reaches. Here
@REM      each hop has to be https and free of userinfo, the chain is bounded, and
@REM      the credentials are attached only when the hop's host is the host the
@REM      properties file named.
@REM
@REM   7. A failure is a failure. Upstream's global trap removes the temporary
@REM      directory and neither rethrows nor breaks, so a terminating download,
@REM      checksum or extraction error is handled and execution resumes at the next
@REM      statement - which eventually reaches the line that publishes MVN_CMD, and
@REM      the batch half then runs whatever is at that path. Here the trap cleans
@REM      up, reports the error on the error stream and exits non-zero, and MVN_CMD
@REM      is written only after the installed distribution has been validated.
@REM
@REM   8. The cache is validated rather than assumed, and installed under a lock.
@REM      Upstream trusts any directory at MAVEN_HOME, and its install swallows a
@REM      failed move whenever the destination merely exists - which is exactly the
@REM      state a previous interrupted install leaves behind. Here a cache counts as
@REM      installed only when bin\mvn.cmd, lib and a mvnw.url marker naming this
@REM      exact distribution are all present; installation is serialised per
@REM      distribution through an exclusively opened lock file; staging happens
@REM      beside the destination so publication is a same-volume directory move;
@REM      an incomplete cache is set aside and rebuilt; and the published result is
@REM      re-validated before its path is handed back to cmd.exe.
@REM
@REM   9. Every derived filesystem path is addressed literally. Upstream passes
@REM      externally derived paths to -Path, which is wildcard-aware, so a valid
@REM      directory containing a bracket resolves to the wrong item or to none.
@REM      Every cmdlet call below that touches a derived path uses -LiteralPath, or
@REM      a System.IO call which is literal by construction, and the ~/.m2 symlink
@REM      target is resolved to an absolute path defensively rather than indexed
@REM      blindly.
@REM
@REM Host requirement. This launcher needs Windows PowerShell 5.1, which ships
@REM with every supported version of Windows, or PowerShell 7 as pwsh.exe. It does
@REM not install one, and it does not silently continue without one. The JDK is
@REM located by the resolved distribution's own mvn.cmd, which honours JAVA_HOME
@REM and then PATH; this launcher does not resolve a JDK itself and does not
@REM download one.
@REM
@REM No committed jar. This module uses the wrapper's only-script distribution
@REM type, declared in .mvn\wrapper\maven-wrapper.properties. In that mode the
@REM launcher itself performs the download, so no maven-wrapper.jar exists in the
@REM repository and no unscanned binary enters the supply chain. The three wrapper
@REM artefacts are exactly this file, mvnw, and the properties file.
@REM
@REM Verified, not trusted. The properties file supplies distributionSha256Sum,
@REM and the download below is rejected unless the archive matches it, so a
@REM tampered or truncated download fails the build closed rather than quietly
@REM producing a build on an unknown toolchain. The comparison lowercases the
@REM computed hash but not the configured one, so distributionSha256Sum has to be
@REM lowercase hex; that constraint is recorded in the properties file and is
@REM enforced here before the cache is consulted.
@REM
@REM Single source of truth. The Maven version and the distribution URL live in
@REM .mvn\wrapper\maven-wrapper.properties and nowhere else - deliberately not in
@REM this script, not in mvnw, not in the Dockerfile and not in the CI workflow -
@REM so upgrading Maven is a one-line change that cannot leave a stale second copy
@REM behind. Set MVNW_VERBOSE=true to have the launcher report the wrapper
@REM version, the resolved URL and the cache directory it uses; those diagnostics
@REM go to the error stream, never to standard output, because standard output is
@REM the channel the batch half reads the resolved command from - see divergence 10.
@REM
@REM Echo suppression. This script uses neither ECHO OFF nor a bare SETLOCAL on
@REM its first line, because it is a polyglot: cmd.exe runs the batch lines, then
@REM re-reads this very file and hands it to PowerShell, so everything from the
@REM first line down to the end-batch marker has to stay inside a PowerShell block
@REM comment. Anything placed above that first line would be parsed by PowerShell
@REM as code and the script would fail outright. Echo is therefore suppressed by
@REM prefixing every executable line with an at-sign, which also covers the first
@REM line that ECHO OFF could not, and the isolation SETLOCAL provides is obtained
@REM by placing it immediately after this comment block.
@REM
@REM The exit code. The resolved mvn.cmd is invoked with CALL so that control
@REM returns here, its ERRORLEVEL is captured, and the local scope is discarded in
@REM the same command that exits with that captured code - so nothing leaks and CI
@REM still sees a failing goal as a non-zero exit status.
@REM ----------------------------------------------------------------------------
@REM

@REM CardDemo modification :: divergences 2 and 3. The whole batch portion runs in
@REM a local scope with delayed expansion disabled, so the caller's environment is
@REM untouched, the private variables below cannot leak, and an exclamation mark in
@REM a path or an argument is read literally however the calling shell was started.
@SETLOCAL DisableDelayedExpansion

@REM Reset unconditionally rather than trusted when pre-set: these four are this
@REM script's own working storage, and an inherited value for any of them would
@REM either be executed as a command or be mistaken for a resolved result.
@SET "__MVNW_CMD__="
@SET "__MVNW_ERROR__="
@SET "__MVNW_EXITCODE__="
@SET "__MVNW_ARG0_NAME__=%~nx0"

@REM CardDemo modification :: divergence 1. The two paths the PowerShell half needs
@REM are published as environment values and read back through $env: below, never
@REM pasted into PowerShell source. The quoted SET form assigns them literally, so a
@REM path containing an ampersand, a pipe or a redirection character is not
@REM reinterpreted by cmd.exe.
@SET "__MVNW_SCRIPTDIR__=%~dp0"
@SET "__MVNW_SCRIPTPATH__=%~f0"

@REM CardDemo modification :: divergence 4. The interpreter is located, not assumed.
@REM The absolute Windows PowerShell path is preferred because it cannot be shadowed
@REM by a PATH entry; Sysnative is the same interpreter as seen by a 32-bit shell on
@REM 64-bit Windows; pwsh.exe covers a host that ships PowerShell 7 only; and a
@REM PATH-resolved powershell.exe is the last resort rather than the first choice.
@SET "__MVNW_PS__="
@IF EXIST "%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" (SET "__MVNW_PS__=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe")
@IF NOT DEFINED __MVNW_PS__ IF EXIST "%SystemRoot%\Sysnative\WindowsPowerShell\v1.0\powershell.exe" (SET "__MVNW_PS__=%SystemRoot%\Sysnative\WindowsPowerShell\v1.0\powershell.exe")
@IF NOT DEFINED __MVNW_PS__ FOR /F "delims=" %%P IN ('WHERE pwsh.exe 2^>NUL') DO @IF NOT DEFINED __MVNW_PS__ (SET "__MVNW_PS__=%%P")
@IF NOT DEFINED __MVNW_PS__ FOR /F "delims=" %%P IN ('WHERE powershell.exe 2^>NUL') DO @IF NOT DEFINED __MVNW_PS__ (SET "__MVNW_PS__=%%P")
@IF NOT DEFINED __MVNW_PS__ GOTO :__mvnw_no_powershell

@REM PSModulePath is cleared for the child only, so an inherited module path cannot
@REM change how the resolver below behaves, and restored immediately afterwards so
@REM the Maven process sees the value the caller set. The quoted form is what keeps
@REM a value containing a command-parsing character from being re-parsed.
@SET "__MVNW_PSMODULEP_SAVE=%PSModulePath%"
@SET "PSModulePath="

@REM Standard output of the resolver carries exactly one line, MVN_CMD=<path>, and
@REM every diagnostic it produces goes to the error stream, which FOR /F does not
@REM capture and which therefore reaches the operator directly. Any other line on
@REM standard output is treated as a failure rather than echoed: echoing a captured
@REM token would hand its content back to the cmd.exe parser, which is the one thing
@REM this dispatch must never do.
@FOR /F "usebackq tokens=1* delims==" %%A IN (`"%__MVNW_PS__%" -NoProfile -NonInteractive -Command "& {$scriptDir=$env:__MVNW_SCRIPTDIR__; $script=$env:__MVNW_ARG0_NAME__; icm -ScriptBlock ([Scriptblock]::Create((Get-Content -Raw -LiteralPath $env:__MVNW_SCRIPTPATH__))) -NoNewScope}"`) DO @(
  IF "%%A"=="MVN_CMD" (SET "__MVNW_CMD__=%%B") ELSE (SET "__MVNW_ERROR__=1")
)
@SET "PSModulePath=%__MVNW_PSMODULEP_SAVE%"
@SET "__MVNW_PSMODULEP_SAVE="
@SET "__MVNW_SCRIPTDIR__="
@SET "__MVNW_SCRIPTPATH__="
@SET "__MVNW_ARG0_NAME__="
@SET "__MVNW_PS__="

@IF DEFINED __MVNW_ERROR__ GOTO :__mvnw_unexpected_output
@IF NOT DEFINED __MVNW_CMD__ GOTO :__mvnw_no_command

@REM The four variables this launcher consumes are cleared before the Maven process
@REM starts, so no credential is inherited by the build. Because this is inside the
@REM local scope, the caller keeps its own values: an authenticated retry in the same
@REM shell behaves exactly as the first attempt did.
@SET "MVNW_VERBOSE="
@SET "MVNW_USERNAME="
@SET "MVNW_PASSWORD="
@SET "MVNW_REPOURL="

@REM CALL so that control returns here, the child's status is captured, and the
@REM local scope is discarded in the same command that exits with that status. The
@REM whole line is parsed before ENDLOCAL runs, so the captured code is expanded
@REM while it is still in scope.
@CALL "%__MVNW_CMD__%" %*
@SET "__MVNW_EXITCODE__=%ERRORLEVEL%"
@ENDLOCAL & EXIT /B %__MVNW_EXITCODE__%

:__mvnw_no_powershell
@ECHO mvnw.cmd needs Windows PowerShell 5.1 or PowerShell 7 to resolve the pinned Maven distribution, and neither was found. >&2
@ECHO Looked for %%SystemRoot%%\System32\WindowsPowerShell\v1.0\powershell.exe, its Sysnative equivalent, then pwsh.exe and powershell.exe on PATH. >&2
@ENDLOCAL & EXIT /B 1

:__mvnw_unexpected_output
@ECHO The Maven Wrapper resolver wrote unexpected content to standard output, so the command it reported cannot be trusted. >&2
@ECHO Re-run with MVNW_VERBOSE=true to see what it produced. >&2
@ENDLOCAL & EXIT /B 1

:__mvnw_no_command
@ECHO Cannot start maven from wrapper >&2
@ENDLOCAL & EXIT /B 1
: end batch / begin powershell #>

# ----------------------------------------------------------------------------
# CardDemo :: carddemo-java :: Windows Maven Wrapper resolver (PowerShell half)
# ----------------------------------------------------------------------------
# cmd.exe re-reads this file and hands everything below the end-batch marker to
# PowerShell. Two variables are already in scope, published by the batch half and
# read back through $env: there rather than pasted into this source: $scriptDir is
# the directory this launcher lives in and $script is its own file name.
#
# Exactly one line ever reaches standard output - MVN_CMD=<path> - because that is
# the channel the batch half reads the resolved command from. Every diagnostic is
# written directly to the error stream instead; see divergence 10 in the header.
# ----------------------------------------------------------------------------

$ErrorActionPreference = "Stop"

# CardDemo modification :: divergence 10. Diagnostics are written straight to the
# error stream rather than through Write-Verbose, and that is deliberate rather
# than stylistic. The batch half reads this script's STANDARD OUTPUT to learn the
# command to run, and where a PowerShell host routes the verbose and warning
# streams is host-specific: Windows PowerShell 5.1 sends them to standard error,
# PowerShell 7 renders them on standard output. Upstream writes its diagnostics to
# the verbose stream and states that they never reach standard output, which is
# true on one of those hosts and false on the other - and on the other one a
# verbose run pollutes the very channel the dispatch parses. Writing to
# [Console]::Error makes the guarantee unconditional: standard output carries
# exactly one line, MVN_CMD=<path>, on every host and in every verbosity.
$mvnwVerbose = ($env:MVNW_VERBOSE -eq 'true')

function Write-MvnwDiagnostic {
  param([Parameter(Mandatory = $true)][AllowEmptyString()][string] $Message)
  if ($mvnwVerbose) {
    [Console]::Error.WriteLine("mvnw.cmd: " + $Message)
  }
}

# The scratch state the trap has to be able to undo. Declared before anything can
# fail, so the trap never reads an undefined variable.
$mvnwLockPath = $null
$mvnwLockStream = $null
$mvnwStagingDir = $null

# CardDemo modification :: divergence 7. Upstream's trap removes its temporary
# directory and then neither rethrows nor breaks, so a terminating download,
# checksum or extraction failure is handled and execution simply continues at the
# next statement - eventually reaching the line that publishes MVN_CMD. This trap
# releases the lock, removes the staging directory, reports the failure on the
# error stream and exits non-zero, so nothing downstream can run on a distribution
# that was never installed. Writing to the console directly cannot re-enter the
# trap the way Write-Error would.
trap {
  $mvnwFailure = $_
  if ($null -ne $mvnwLockStream) {
    try { $mvnwLockStream.Dispose() } catch { }
    if ($mvnwLockPath) {
      try { Remove-Item -LiteralPath $mvnwLockPath -Force } catch { }
    }
  }
  if ($mvnwStagingDir) {
    try { Remove-Item -LiteralPath $mvnwStagingDir -Recurse -Force } catch { }
  }
  [Console]::Error.WriteLine("mvnw.cmd: " + $mvnwFailure.Exception.Message)
  exit 1
}

# CardDemo addition :: divergence 5. Replaces a userinfo component with fixed text,
# so a diagnostic can quote the URL that caused it without quoting a credential
# that was embedded in that URL. The pattern cannot cross a path separator, so an
# at-sign in a path segment is left alone.
function Get-RedactedUri {
  param([Parameter(Mandatory = $true)][AllowEmptyString()][string] $Uri)
  return ($Uri -replace '^([A-Za-z][A-Za-z0-9+.-]*://)[^/@]*@', '$1***:***@')
}

# CardDemo addition :: divergence 5. The transport contract, asserted rather than
# assumed, and asserted for every URL this launcher is about to act on - the
# configured one, the MVNW_REPOURL rewrite of it, and every redirect hop.
function Assert-SecureDistributionUri {
  param(
    [Parameter(Mandatory = $true)][string] $Uri,
    [Parameter(Mandatory = $true)][string] $Source
  )
  $parsed = $null
  if (-not [System.Uri]::TryCreate($Uri, [System.UriKind]::Absolute, [ref] $parsed)) {
    throw "$Source is not an absolute URL: '$(Get-RedactedUri $Uri)'"
  }
  if ($parsed.Scheme -ne 'https') {
    throw "$Source must be an https URL, so the distribution cannot be fetched over a channel that permits substitution or credential capture; found '$(Get-RedactedUri $Uri)'"
  }
  if ($parsed.UserInfo) {
    throw "$Source must not embed credentials in the URL; set MVNW_USERNAME and MVNW_PASSWORD instead, which are offered only to the host the URL names. Found '$(Get-RedactedUri $Uri)'"
  }
  return $parsed
}

# CardDemo addition :: divergence 8. A cached distribution counts as installed only
# when the launcher it will be executed from is there, the library directory it
# needs is there, and the marker names this exact distribution. Upstream trusts the
# existence of the directory alone, so a partial install is executed rather than
# repaired - by this invocation and by every later one.
function Test-DistributionComplete {
  param(
    [Parameter(Mandatory = $true)][string] $DistributionHome,
    [Parameter(Mandatory = $true)][string] $LauncherName,
    [Parameter(Mandatory = $true)][string] $MarkerName,
    [Parameter(Mandatory = $true)][string] $ExpectedUrl
  )
  if (-not (Test-Path -LiteralPath $DistributionHome -PathType Container)) { return $false }
  if (-not (Test-Path -LiteralPath (Join-Path $DistributionHome (Join-Path 'bin' $LauncherName)) -PathType Leaf)) { return $false }
  if (-not (Test-Path -LiteralPath (Join-Path $DistributionHome 'lib') -PathType Container)) { return $false }
  $markerPath = Join-Path $DistributionHome $MarkerName
  if (-not (Test-Path -LiteralPath $markerPath -PathType Leaf)) { return $false }
  $recorded = $null
  try { $recorded = Get-Content -Raw -LiteralPath $markerPath } catch { return $false }
  if ($null -eq $recorded) { return $false }
  return ($recorded.Trim() -ceq $ExpectedUrl)
}

# CardDemo modification :: divergence 6. Upstream downloads with
# System.Net.WebClient, which follows redirects on its own and answers an
# authentication challenge from whatever host the chain reaches. Here the redirects
# are followed one hop at a time, every hop has to be https and free of embedded
# userinfo, the chain is bounded, and the credentials are attached only when the
# hop's host is the host the properties file named. PreAuthenticate stays off, so
# they are sent in answer to a challenge rather than volunteered.
function Invoke-MvnwDownload {
  param(
    [Parameter(Mandatory = $true)][string] $Uri,
    [Parameter(Mandatory = $true)][string] $Destination,
    [Parameter(Mandatory = $false)][System.Net.NetworkCredential] $Credential
  )
  $maximumHops = 5
  $target = Assert-SecureDistributionUri -Uri $Uri -Source 'the distribution URL'
  $allowedHost = $target.Host
  $current = $target
  for ($hop = 0; $hop -le $maximumHops; $hop++) {
    [void] (Assert-SecureDistributionUri -Uri $current.AbsoluteUri -Source 'the redirect target')
    $request = [System.Net.HttpWebRequest] ([System.Net.WebRequest]::Create($current))
    $request.AllowAutoRedirect = $false
    $request.Method = 'GET'
    $request.Timeout = 60000
    $request.ReadWriteTimeout = 600000
    # An identified request rather than an anonymous one. A mirror behind a web
    # application firewall commonly answers 403 to a request that names no client,
    # which upstream's WebClient - and any hand-rolled request that omits this - runs
    # into as a bare "forbidden" with no indication of why.
    $request.UserAgent = 'carddemo-maven-wrapper'
    if (($null -ne $Credential) -and ($current.Host -eq $allowedHost)) {
      $request.Credentials = $Credential
      $request.PreAuthenticate = $false
    }
    $response = $null
    try {
      $response = [System.Net.HttpWebResponse] ($request.GetResponse())
    } catch [System.Net.WebException] {
      if ($null -eq $_.Exception.Response) { throw }
      $response = [System.Net.HttpWebResponse] ($_.Exception.Response)
    }
    $status = [int] $response.StatusCode
    if (@(301, 302, 303, 307, 308) -contains $status) {
      $location = $response.Headers['Location']
      $response.Close()
      if (-not $location) {
        throw "The distribution URL answered $status with no Location header, so the redirect cannot be followed"
      }
      $current = New-Object System.Uri($current, $location)
      Write-MvnwDiagnostic "following a redirect to $(Get-RedactedUri $current.AbsoluteUri)"
      continue
    }
    if ($status -ne 200) {
      $response.Close()
      throw "Unexpected HTTP status $status fetching $(Get-RedactedUri $current.AbsoluteUri)"
    }
    try {
      $responseStream = $response.GetResponseStream()
      try {
        $fileStream = [System.IO.File]::Open($Destination, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
        try { $responseStream.CopyTo($fileStream) } finally { $fileStream.Dispose() }
      } finally { $responseStream.Dispose() }
    } finally { $response.Close() }
    return
  }
  throw "Refusing to follow more than $maximumHops redirects while fetching the Maven distribution"
}

# ---- what the properties file says, read once ------------------------------
# CardDemo modification :: divergence 9. -LiteralPath, so a checkout path
# containing a bracket is read literally rather than expanded as a wildcard.
$mvnwPropertiesPath = Join-Path $scriptDir (Join-Path '.mvn' (Join-Path 'wrapper' 'maven-wrapper.properties'))
$mvnwProperties = (Get-Content -Raw -LiteralPath $mvnwPropertiesPath | ConvertFrom-StringData)

$distributionUrl = $mvnwProperties.distributionUrl
if (!$distributionUrl) {
  throw "cannot read distributionUrl property in $mvnwPropertiesPath"
}

# wrapperVersion records which Apache Maven Wrapper release these launcher scripts
# were generated from, so a build log can evidence the launcher generation that
# produced it. It is informational only - the Maven version itself comes solely
# from distributionUrl - so an absent key degrades to "unknown".
$wrapperVersion = $mvnwProperties.wrapperVersion
if (!$wrapperVersion) {
  $wrapperVersion = "unknown"
}
Write-MvnwDiagnostic "Apache Maven Wrapper $wrapperVersion, configured by $mvnwPropertiesPath"
Write-MvnwDiagnostic "configured distributionUrl: $(Get-RedactedUri $distributionUrl)"

# CardDemo modification :: divergence 5. Everything the properties file says about
# the distribution is checked HERE, before the cache is consulted and long before
# anything is fetched. Ordering is the point: a launcher that validates after a
# cache hit leaves a machine that already holds a distribution running whatever it
# holds, and the same configuration then behaves differently on a warm machine than
# on a cold one.
[void] (Assert-SecureDistributionUri -Uri $distributionUrl -Source "distributionUrl in $mvnwPropertiesPath")

$distributionSha256Sum = $mvnwProperties.distributionSha256Sum
if (!$distributionSha256Sum) {
  throw "distributionSha256Sum is not set in $mvnwPropertiesPath. This launcher refuses to install a Maven distribution it cannot verify; add the SHA-256 of the pinned archive rather than removing the check."
}
if ($distributionSha256Sum -cnotmatch '^[0-9a-f]{64}$') {
  throw "distributionSha256Sum in $mvnwPropertiesPath must be exactly 64 lowercase hexadecimal characters, and it is not. A malformed digest cannot verify anything, so the build stops here rather than at a mismatch that would look like a tampered archive."
}
Write-MvnwDiagnostic "distributionSha256Sum: present and well formed; verification of the downloaded archive is mandatory"

# CardDemo modification :: divergence 5. The archive shape, against exactly the
# pattern the properties file documents as a guarantee: an apache-maven -bin.zip
# and nothing else. Upstream's test only requires that an extension can be
# stripped, so foo.zip, foo.tar.gz and foo.exe all pass a check that advertises
# -bin.zip, and it runs after the cache lookup rather than before it. maven-mvnd
# is refused with its own message: it publishes no digest this launcher could hold
# it to, the digest is not optional here, and upstream's remedy for that collision
# is to remove distributionSha256Sum - the one remedy this module does not accept.
$distributionUrlName = $distributionUrl -replace '^.*/', ''
if ($distributionUrlName -cmatch '^maven-mvnd-') {
  throw "maven-mvnd is not supported by this launcher: distributionSha256Sum is mandatory here and an mvnd distribution cannot be verified against it. Pin an apache-maven -bin.zip in $mvnwPropertiesPath."
}
if ($distributionUrlName -cnotmatch '^[^/\\]+-bin\.zip$') {
  throw "distributionUrl is not valid, must name an apache-maven *-bin.zip archive, but found '$(Get-RedactedUri $distributionUrl)'"
}
$distributionUrlNameMain = $distributionUrlName -replace '\.[^.]*$', '' -replace '-bin$', ''
$MVN_CMD = $script -replace '^mvnw', 'mvn'

# ---- apply MVNW_REPOURL and calculate MAVEN_HOME ---------------------------
# maven home pattern: ~/.m2/wrapper/dists/apache-maven-<version>/<hash>
if ($env:MVNW_REPOURL) {
  $MVNW_REPO_PATTERN = "/org/apache/maven/"
  $distributionUrl = "$env:MVNW_REPOURL$MVNW_REPO_PATTERN$($distributionUrl -replace "^.*$MVNW_REPO_PATTERN", '')"
  # The override is held to the same transport contract as the configured URL.
  # Upstream applies it unchecked, so a mirror base of http:// or one carrying
  # userinfo silently downgrades a verified-over-TLS fetch - and it is the
  # override, not the pinned URL, that an environment can set without editing a
  # committed file.
  [void] (Assert-SecureDistributionUri -Uri $distributionUrl -Source 'the distribution URL produced by MVNW_REPOURL')
  $distributionUrlName = $distributionUrl -replace '^.*/', ''
  if ($distributionUrlName -cnotmatch '^[^/\\]+-bin\.zip$') {
    throw "the distribution URL produced by MVNW_REPOURL must name an apache-maven *-bin.zip archive, but found '$(Get-RedactedUri $distributionUrl)'"
  }
  $distributionUrlNameMain = $distributionUrlName -replace '\.[^.]*$', '' -replace '-bin$', ''
}
Write-MvnwDiagnostic "resolved distributionUrl: $(Get-RedactedUri $distributionUrl)"

$MAVEN_M2_PATH = Join-Path $HOME '.m2'
if ($env:MAVEN_USER_HOME) {
  $MAVEN_M2_PATH = $env:MAVEN_USER_HOME
}
$MAVEN_M2_PATH = [System.IO.Path]::GetFullPath($MAVEN_M2_PATH)
[void] [System.IO.Directory]::CreateDirectory($MAVEN_M2_PATH)

# CardDemo modification :: divergence 9. Upstream indexes (Get-Item $path).Target[0]
# with a wildcard-aware -Path and no test for whether the item is a link at all.
# Here the reparse point is tested for first, the target is read defensively
# whether it arrives as a string or as a collection, and a relative target is
# resolved against the link's own parent so the result is always absolute.
$MAVEN_M2_ITEM = Get-Item -LiteralPath $MAVEN_M2_PATH -Force
$MAVEN_M2_TARGET = $null
if (($MAVEN_M2_ITEM.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -eq [System.IO.FileAttributes]::ReparsePoint) {
  $candidateTarget = $MAVEN_M2_ITEM.Target
  if ($candidateTarget -is [string]) {
    $MAVEN_M2_TARGET = $candidateTarget
  } elseif (($null -ne $candidateTarget) -and (@($candidateTarget).Count -gt 0)) {
    $MAVEN_M2_TARGET = @($candidateTarget)[0]
  }
}
if ($MAVEN_M2_TARGET) {
  if (-not [System.IO.Path]::IsPathRooted($MAVEN_M2_TARGET)) {
    $MAVEN_M2_TARGET = Join-Path (Split-Path -LiteralPath $MAVEN_M2_PATH -Parent) $MAVEN_M2_TARGET
  }
  $MAVEN_WRAPPER_DISTS = [System.IO.Path]::GetFullPath((Join-Path $MAVEN_M2_TARGET (Join-Path 'wrapper' 'dists')))
} else {
  $MAVEN_WRAPPER_DISTS = Join-Path $MAVEN_M2_PATH (Join-Path 'wrapper' 'dists')
}

$MAVEN_HOME_PARENT = Join-Path $MAVEN_WRAPPER_DISTS $distributionUrlNameMain
$MAVEN_HOME_NAME = ([System.Security.Cryptography.SHA256]::Create().ComputeHash([byte[]][char[]]$distributionUrl) | ForEach-Object { $_.ToString("x2") }) -join ''
$MAVEN_HOME = Join-Path $MAVEN_HOME_PARENT $MAVEN_HOME_NAME
$MVNW_URL_MARKER = 'mvnw.url'
Write-MvnwDiagnostic "wrapper cache directory: $MAVEN_HOME"

# The one place the resolved command is produced, reached only from a validated
# cache. Backslashes throughout, because the batch half hands this straight to
# CALL.
function Write-ResolvedMavenCommand {
  param([Parameter(Mandatory = $true)][string] $DistributionHome, [Parameter(Mandatory = $true)][string] $LauncherName)
  Write-Output ("MVN_CMD=" + ((Join-Path $DistributionHome (Join-Path 'bin' $LauncherName)) -replace '/', '\'))
}

# ---- fast path: a cache that is demonstrably complete ----------------------
if (Test-DistributionComplete -DistributionHome $MAVEN_HOME -LauncherName $MVN_CMD -MarkerName $MVNW_URL_MARKER -ExpectedUrl $distributionUrl) {
  Write-MvnwDiagnostic "found a complete MAVEN_HOME at $MAVEN_HOME"
  Write-ResolvedMavenCommand -DistributionHome $MAVEN_HOME -LauncherName $MVN_CMD
  exit 0
}

# The cache parent has to exist before either the lock file or the staging
# directory can be created inside it, and both are created inside it
# deliberately: the lock is per distribution, and staging beside the destination
# is what makes publication a move on one volume rather than a copy across two.
[void] [System.IO.Directory]::CreateDirectory($MAVEN_HOME_PARENT)

# CardDemo addition :: divergence 8. Installation is serialised per distribution.
# An exclusively opened lock file is the atomic test-and-set here, and it is
# self-healing without any liveness heuristic: if the invocation holding it dies,
# Windows closes its handle, so a waiter can prove the lock is abandoned simply by
# opening it exclusively itself. A lock that is still held cannot be opened, and
# therefore cannot be stolen from a build that is still running.
$mvnwLockPath = "$MAVEN_HOME.lock"
$mvnwLockTimeout = 300
if ($env:MVNW_LOCK_TIMEOUT_SECONDS) {
  $parsedTimeout = 0
  if ([int]::TryParse($env:MVNW_LOCK_TIMEOUT_SECONDS, [ref] $parsedTimeout) -and ($parsedTimeout -ge 0)) {
    $mvnwLockTimeout = $parsedTimeout
  }
}
$mvnwWaited = 0
$mvnwUseExisting = $false
while ($null -eq $mvnwLockStream) {
  try {
    $mvnwLockStream = [System.IO.File]::Open($mvnwLockPath, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
  } catch [System.IO.IOException] {
    if (Test-DistributionComplete -DistributionHome $MAVEN_HOME -LauncherName $MVN_CMD -MarkerName $MVNW_URL_MARKER -ExpectedUrl $distributionUrl) {
      Write-MvnwDiagnostic "another invocation finished installing $MAVEN_HOME while this one waited"
      $mvnwUseExisting = $true
      break
    }
    if ($mvnwWaited -ge $mvnwLockTimeout) {
      throw "waited $($mvnwLockTimeout)s for the Maven distribution install lock at $mvnwLockPath and it is still held. If no other build is running, delete that file and retry; set MVNW_LOCK_TIMEOUT_SECONDS to wait longer."
    }
    if ($mvnwWaited -ge 5) {
      try {
        $abandonedLock = [System.IO.File]::Open($mvnwLockPath, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
        $abandonedLock.Dispose()
        Write-MvnwDiagnostic "reclaiming the install lock at $mvnwLockPath left behind by an invocation that did not finish"
        Remove-Item -LiteralPath $mvnwLockPath -Force
      } catch [System.IO.IOException] {
        Write-MvnwDiagnostic "the install lock at $mvnwLockPath is held by a running invocation"
      }
    }
    Start-Sleep -Seconds 1
    $mvnwWaited = $mvnwWaited + 1
  }
}

if (-not $mvnwUseExisting) {
  # Re-checked under the lock: between the fast path above and the lock being
  # granted, another invocation may have installed the distribution in full.
  if (Test-DistributionComplete -DistributionHome $MAVEN_HOME -LauncherName $MVN_CMD -MarkerName $MVNW_URL_MARKER -ExpectedUrl $distributionUrl) {
    Write-MvnwDiagnostic "found a complete MAVEN_HOME at $MAVEN_HOME"
    $mvnwUseExisting = $true
  }
}

if (-not $mvnwUseExisting) {
  # An incomplete cache is set aside and then removed, rather than executed. The
  # rename comes first so a reader that already opened the old path keeps reading
  # a consistent tree and the rebuild cannot race the delete.
  if (Test-Path -LiteralPath $MAVEN_HOME) {
    $mvnwQuarantine = "$MAVEN_HOME.invalid." + [System.Guid]::NewGuid().ToString('N')
    [Console]::Error.WriteLine("Discarding an incomplete Maven distribution at $MAVEN_HOME and installing it again.")
    [System.IO.Directory]::Move($MAVEN_HOME, $mvnwQuarantine)
    Remove-Item -LiteralPath $mvnwQuarantine -Recurse -Force
  }

  # A staging directory is named after the cache entry it is being built for, so
  # any left behind by an invocation that was killed outright belongs to this
  # distribution alone and can be removed here, under the lock, rather than
  # accumulating in the user's Maven home indefinitely.
  foreach ($orphan in @(Get-ChildItem -LiteralPath $MAVEN_HOME_PARENT -Directory -Filter "$MAVEN_HOME_NAME.install.*")) {
    Write-MvnwDiagnostic "removing a staging directory left behind at $($orphan.FullName)"
    Remove-Item -LiteralPath $orphan.FullName -Recurse -Force
  }

  $mvnwStagingDir = "$MAVEN_HOME.install." + [System.Guid]::NewGuid().ToString('N')
  [void] [System.IO.Directory]::CreateDirectory($mvnwStagingDir)
  $mvnwArchive = Join-Path $mvnwStagingDir $distributionUrlName

  Write-MvnwDiagnostic "Couldn't find MAVEN_HOME, downloading and installing it ..."
  Write-MvnwDiagnostic "Downloading from: $(Get-RedactedUri $distributionUrl)"
  Write-MvnwDiagnostic "Downloading to: $mvnwArchive"

  try {
    [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12 -bor [System.Net.SecurityProtocolType]::Tls13
  } catch {
    [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12
  }

  $mvnwCredential = $null
  if ($env:MVNW_USERNAME -and $env:MVNW_PASSWORD) {
    $mvnwCredential = New-Object System.Net.NetworkCredential($env:MVNW_USERNAME, $env:MVNW_PASSWORD)
  }
  Invoke-MvnwDownload -Uri $distributionUrl -Destination $mvnwArchive -Credential $mvnwCredential

  # Validate the SHA-256 sum of the Maven distribution zip file. Unconditional,
  # because distributionSha256Sum was required to be present and well formed
  # before anything was fetched; upstream wraps this in a test for the property
  # being non-empty, which is the switch that turns verification off. Get-FileHash
  # is imported explicitly only when it is not already available, so the import
  # cannot itself fail the run on a host that already has it.
  if (-not (Get-Command -Name 'Get-FileHash' -ErrorAction SilentlyContinue)) {
    Import-Module -Name (Join-Path $PSHOME (Join-Path 'Modules' 'Microsoft.PowerShell.Utility')) -Function 'Get-FileHash'
  }
  $mvnwComputedSha256 = (Get-FileHash -LiteralPath $mvnwArchive -Algorithm SHA256).Hash.ToLowerInvariant()
  if ($mvnwComputedSha256 -cne $distributionSha256Sum) {
    throw "Failed to validate Maven distribution SHA-256, your Maven distribution might be compromised. If you updated your Maven version, you need to update the specified distributionSha256Sum property."
  }
  Write-MvnwDiagnostic "the downloaded archive matches distributionSha256Sum"

  Expand-Archive -LiteralPath $mvnwArchive -DestinationPath $mvnwStagingDir
  Remove-Item -LiteralPath $mvnwArchive -Force

  # Find the actual extracted directory name (handles snapshots where the file
  # name and the directory name differ).
  $mvnwExtracted = $null
  $mvnwExpected = Join-Path $mvnwStagingDir $distributionUrlNameMain
  if ((Test-Path -LiteralPath $mvnwExpected -PathType Container) -and
      (Test-Path -LiteralPath (Join-Path $mvnwExpected (Join-Path 'bin' $MVN_CMD)) -PathType Leaf)) {
    $mvnwExtracted = $mvnwExpected
  }
  if ($null -eq $mvnwExtracted) {
    foreach ($candidate in @(Get-ChildItem -LiteralPath $mvnwStagingDir -Directory)) {
      if (Test-Path -LiteralPath (Join-Path $candidate.FullName (Join-Path 'bin' $MVN_CMD)) -PathType Leaf) {
        $mvnwExtracted = $candidate.FullName
        break
      }
    }
  }
  if ($null -eq $mvnwExtracted) {
    throw "Could not find Maven distribution directory in extracted archive"
  }
  Write-MvnwDiagnostic "Found extracted Maven distribution directory: $mvnwExtracted"

  # Publication, in the one order that makes a partially installed cache
  # unobservable. The marker is written while the tree is still staged and still
  # under a name no other invocation looks for, so the directory becomes complete
  # before it becomes visible; the move is then a single operation on one volume.
  # Upstream instead renames, moves, and swallows a failed move whenever the
  # destination merely exists - which is exactly the state a previous interrupted
  # install leaves behind.
  Set-Content -LiteralPath (Join-Path $mvnwExtracted $MVNW_URL_MARKER) -Value $distributionUrl -Encoding ASCII
  [System.IO.Directory]::Move($mvnwExtracted, $MAVEN_HOME)
  Remove-Item -LiteralPath $mvnwStagingDir -Recurse -Force
  $mvnwStagingDir = $null

  if (-not (Test-DistributionComplete -DistributionHome $MAVEN_HOME -LauncherName $MVN_CMD -MarkerName $MVNW_URL_MARKER -ExpectedUrl $distributionUrl)) {
    throw "the Maven distribution published to $MAVEN_HOME is not complete; refusing to hand a build to it"
  }
}

# The lock is released before the command is published, so the build that follows
# never runs while this launcher still holds it.
if ($null -ne $mvnwLockStream) {
  $mvnwLockStream.Dispose()
  $mvnwLockStream = $null
  Remove-Item -LiteralPath $mvnwLockPath -Force
}

Write-ResolvedMavenCommand -DistributionHome $MAVEN_HOME -LauncherName $MVN_CMD
exit 0
