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
@REM Apache Maven Wrapper startup batch script, version 3.3.4
@REM
@REM Optional ENV vars
@REM   MVNW_REPOURL - repo url base for downloading maven distribution
@REM   MVNW_USERNAME/MVNW_PASSWORD - user and password for downloading maven
@REM   MVNW_VERBOSE - true: enable verbose log; others: silence the output
@REM   MAVEN_USER_HOME - Maven user home; the resolved distribution is cached
@REM                     under %MAVEN_USER_HOME%\wrapper\dists (default ~/.m2)
@REM   MAVEN_OPTS - JVM options for the Maven process; forwarded untouched
@REM   MAVEN_ARGS - default command line arguments; forwarded untouched
@REM   JAVA_HOME - the JDK the Maven process runs on; forwarded untouched
@REM
@REM Only the MVNW_* variables are consumed by this launcher, and the batch
@REM portion clears exactly MVNW_USERNAME and MVNW_PASSWORD before handing
@REM control over, so JAVA_HOME, MAVEN_OPTS and MAVEN_ARGS reach the real mvn
@REM launcher unmodified and behave there exactly as they do for a locally
@REM installed Maven. That pass-through is what lets the CI workflow export
@REM MAVEN_ARGS once and have every mvnw.cmd invocation honour it.
@REM ----------------------------------------------------------------------------

@REM ----------------------------------------------------------------------------
@REM CardDemo :: carddemo-java :: Windows Maven Wrapper launcher
@REM ----------------------------------------------------------------------------
@REM Purpose. This script is the mechanism by which a clean checkout of this
@REM repository builds with no preinstalled Maven: a JDK is the only
@REM prerequisite. It resolves, downloads, verifies, caches and then executes
@REM the exact Apache Maven distribution this module is pinned to, and forwards
@REM every argument to it unmodified. Running "mvnw.cmd clean verify" from the
@REM carddemo-java directory therefore behaves exactly as "./mvnw clean verify"
@REM does on Linux or macOS, which is what makes the zero-warning
@REM deployable-artifact requirement reproducible rather than machine-dependent.
@REM This file and mvnw are deliberately behaviourally equivalent: they read the
@REM same properties file, honour the same ENV vars and resolve the same pinned
@REM Maven, so one README section describes both without qualification.
@REM
@REM Provenance. The executable body below is the canonical Apache Maven
@REM Wrapper Windows launcher, version 3.3.4, taken unmodified from the
@REM only-script distribution published on Maven Central as
@REM   org.apache.maven.wrapper:maven-wrapper-distribution:3.3.4:zip:only-script
@REM whose mvnw.cmd member has SHA-256
@REM   46eedb8419bd14fe70d5bb2916d7b6f51806e51b39d5b76a42610384ca929c1c
@REM so the resolution, download, checksum and dispatch logic can be
@REM re-verified against upstream at any time. Using the canonical script rather
@REM than a bespoke downloader is deliberate: a developer's existing
@REM expectations of mvnw.cmd are part of the contract.
@REM
@REM Divergence from upstream, stated so a diff against 3.3.4 is explainable.
@REM Four changes are presentational - the licence header, these comment blocks,
@REM the ENV vars documented above, and the wrapperVersion lookup with its
@REM verbose logging - and none of them alters control flow. One change is
@REM substantive and is a defect fix, described under "Paths are passed, never
@REM pasted" below. The edited lines are marked inline with "CardDemo
@REM modification".
@REM
@REM No committed jar. This module uses the wrapper's only-script distribution
@REM type, declared in .mvn\wrapper\maven-wrapper.properties. In that mode the
@REM launcher itself performs the download, so no maven-wrapper.jar exists in
@REM the repository and no unscanned binary enters the supply chain. The three
@REM wrapper artefacts are exactly this file, mvnw, and the properties file.
@REM
@REM Verified, not trusted. The properties file supplies distributionSha256Sum,
@REM and the download below is rejected unless the archive matches it, so a
@REM tampered or truncated download fails the build closed rather than quietly
@REM producing a build on an unknown toolchain. The comparison lowercases the
@REM computed hash but not the configured one, so distributionSha256Sum has to
@REM be lowercase hex; that constraint is recorded in the properties file.
@REM
@REM Single source of truth. The Maven version and the distribution URL live in
@REM .mvn\wrapper\maven-wrapper.properties and nowhere else - deliberately not
@REM in this script, not in mvnw, not in the Dockerfile and not in the CI
@REM workflow - so upgrading Maven is a one-line change that cannot leave a
@REM stale second copy behind. Set MVNW_VERBOSE=true to have the launcher report
@REM the wrapper version, the resolved URL and the cache directory it uses.
@REM
@REM Paths are passed, never pasted. cmd.exe cannot hand arguments to the
@REM PowerShell half of this polyglot, so the two paths that half needs - the
@REM directory this script lives in and this script's own full path - have to
@REM cross the boundary somehow. Upstream 3.3.4 crosses it by pasting %~dp0 and
@REM %~f0 into single-quoted PowerShell string literals inside the dispatch
@REM line. An apostrophe is a legal character in a Windows path, and one
@REM appearing in a checkout path closes the literal early: at best the launcher
@REM dies on a parse error in a valid working directory, at worst a crafted path
@REM closes the literal and the remainder of that path is parsed as PowerShell
@REM and executed inside the generated script block. Both paths are therefore
@REM published as environment variables instead and read back on the PowerShell
@REM side through $env:, so no path text is ever parsed as source and no
@REM escaping scheme has to be maintained. The quoted SET form is used so that a
@REM path containing a command-parsing character - an ampersand, a pipe, a
@REM redirection - is assigned literally rather than reinterpreted by cmd.exe,
@REM and every Get-Content that resolves a caller-supplied path is given
@REM -LiteralPath so that a path containing a bracket is read literally rather
@REM than expanded as a wildcard - which covers this script's own path and the
@REM three reads of the properties file beneath it, all four being derived from
@REM %~dp0 or %~f0. The two variables are cleared immediately after the
@REM dispatch, with the other temporaries. The values themselves are unchanged,
@REM trailing separator included, so the PowerShell half sees exactly what it
@REM saw before.
@REM
@REM Echo suppression and variable hygiene. This script deliberately uses
@REM neither ECHO OFF nor SETLOCAL, because it is a polyglot: cmd.exe runs the
@REM batch lines, then re-reads this very file and hands it to PowerShell, so
@REM everything from the first line down to the end-batch marker has to stay
@REM inside a PowerShell block comment. Anything placed above that first line
@REM would be parsed by PowerShell as code and the script would fail outright.
@REM Upstream therefore suppresses echo by prefixing every executable line with
@REM an at-sign, which also covers the first line that ECHO OFF could not, and
@REM obtains the isolation SETLOCAL would give by explicitly clearing its own
@REM temporaries together with MVNW_USERNAME and MVNW_PASSWORD and restoring
@REM PSModulePath before dispatching. Nothing leaks into the caller's shell.
@REM
@REM Locating the JDK, and the exit code. Both are delegated to the mvn.cmd of
@REM the resolved distribution, which honours JAVA_HOME, falls back to java on
@REM PATH, prints a clear diagnostic on the error stream when neither yields a
@REM usable JDK, and terminates through "exit /b" carrying its own error code.
@REM Because that mvn.cmd is invoked below without CALL, cmd.exe lets the callee
@REM replace this script instead of returning to it, so the error code becomes
@REM the exit code of mvnw.cmd and CI detects a failing goal correctly. The
@REM diagnostic line after the dispatch is reachable only when no Maven command
@REM could be resolved at all, and it exits non-zero in its own right.
@REM ----------------------------------------------------------------------------
@REM

@IF "%__MVNW_ARG0_NAME__%"=="" (SET "__MVNW_ARG0_NAME__=%~nx0")
@SET __MVNW_CMD__=
@SET __MVNW_ERROR__=
@REM CardDemo modification :: the two paths the PowerShell half needs are published
@REM as environment values and read back through $env: below, never pasted into
@REM PowerShell source. See "Paths are passed, never pasted" above.
@SET "__MVNW_SCRIPTDIR__=%~dp0"
@SET "__MVNW_SCRIPTPATH__=%~f0"
@SET __MVNW_PSMODULEP_SAVE=%PSModulePath%
@SET PSModulePath=
@FOR /F "usebackq tokens=1* delims==" %%A IN (`powershell -noprofile "& {$scriptDir=$env:__MVNW_SCRIPTDIR__; $script=$env:__MVNW_ARG0_NAME__; icm -ScriptBlock ([Scriptblock]::Create((Get-Content -Raw -LiteralPath $env:__MVNW_SCRIPTPATH__))) -NoNewScope}"`) DO @(
  IF "%%A"=="MVN_CMD" (set __MVNW_CMD__=%%B) ELSE IF "%%B"=="" (echo %%A) ELSE (echo %%A=%%B)
)
@SET PSModulePath=%__MVNW_PSMODULEP_SAVE%
@SET __MVNW_PSMODULEP_SAVE=
@SET __MVNW_SCRIPTDIR__=
@SET __MVNW_SCRIPTPATH__=
@SET __MVNW_ARG0_NAME__=
@SET MVNW_USERNAME=
@SET MVNW_PASSWORD=
@IF NOT "%__MVNW_CMD__%"=="" ("%__MVNW_CMD__%" %*)
@echo Cannot start maven from wrapper >&2 && exit /b 1
@GOTO :EOF
: end batch / begin powershell #>

$ErrorActionPreference = "Stop"
if ($env:MVNW_VERBOSE -eq "true") {
  $VerbosePreference = "Continue"
}

# calculate distributionUrl, requires .mvn/wrapper/maven-wrapper.properties
$distributionUrl = (Get-Content -Raw -LiteralPath "$scriptDir/.mvn/wrapper/maven-wrapper.properties" | ConvertFrom-StringData).distributionUrl
if (!$distributionUrl) {
  Write-Error "cannot read distributionUrl property in $scriptDir/.mvn/wrapper/maven-wrapper.properties"
}

# wrapperVersion is read from the same properties file that supplies
# distributionUrl. It records which Apache Maven Wrapper release these
# launcher scripts were generated from, so a build log can evidence the
# launcher generation that produced it. It is informational only - the Maven
# version itself comes solely from distributionUrl - so an absent key
# degrades to "unknown" rather than failing the build.
#
# Every diagnostic below goes to the verbose stream and never to standard
# output. The batch portion parses this script's standard output to pick up
# MVN_CMD, so writing diagnostics there would corrupt the dispatch.
$wrapperVersion = (Get-Content -Raw -LiteralPath "$scriptDir/.mvn/wrapper/maven-wrapper.properties" | ConvertFrom-StringData).wrapperVersion
if (!$wrapperVersion) {
  $wrapperVersion = "unknown"
}
Write-Verbose "Apache Maven Wrapper $wrapperVersion, configured by $scriptDir/.mvn/wrapper/maven-wrapper.properties"
Write-Verbose "configured distributionUrl: $distributionUrl"

switch -wildcard -casesensitive ( $($distributionUrl -replace '^.*/','') ) {
  "maven-mvnd-*" {
    $USE_MVND = $true
    $distributionUrl = $distributionUrl -replace '-bin\.[^.]*$',"-windows-amd64.zip"
    $MVN_CMD = "mvnd.cmd"
    break
  }
  default {
    $USE_MVND = $false
    $MVN_CMD = $script -replace '^mvnw','mvn'
    break
  }
}

# apply MVNW_REPOURL and calculate MAVEN_HOME
# maven home pattern: ~/.m2/wrapper/dists/{apache-maven-<version>,maven-mvnd-<version>-<platform>}/<hash>
if ($env:MVNW_REPOURL) {
  $MVNW_REPO_PATTERN = if ($USE_MVND -eq $False) { "/org/apache/maven/" } else { "/maven/mvnd/" }
  $distributionUrl = "$env:MVNW_REPOURL$MVNW_REPO_PATTERN$($distributionUrl -replace "^.*$MVNW_REPO_PATTERN",'')"
}
$distributionUrlName = $distributionUrl -replace '^.*/',''
$distributionUrlNameMain = $distributionUrlName -replace '\.[^.]*$','' -replace '-bin$',''

$MAVEN_M2_PATH = "$HOME/.m2"
if ($env:MAVEN_USER_HOME) {
  $MAVEN_M2_PATH = "$env:MAVEN_USER_HOME"
}

if (-not (Test-Path -Path $MAVEN_M2_PATH)) {
    New-Item -Path $MAVEN_M2_PATH -ItemType Directory | Out-Null
}

$MAVEN_WRAPPER_DISTS = $null
if ((Get-Item $MAVEN_M2_PATH).Target[0] -eq $null) {
  $MAVEN_WRAPPER_DISTS = "$MAVEN_M2_PATH/wrapper/dists"
} else {
  $MAVEN_WRAPPER_DISTS = (Get-Item $MAVEN_M2_PATH).Target[0] + "/wrapper/dists"
}

$MAVEN_HOME_PARENT = "$MAVEN_WRAPPER_DISTS/$distributionUrlNameMain"
$MAVEN_HOME_NAME = ([System.Security.Cryptography.SHA256]::Create().ComputeHash([byte[]][char[]]$distributionUrl) | ForEach-Object {$_.ToString("x2")}) -join ''
$MAVEN_HOME = "$MAVEN_HOME_PARENT/$MAVEN_HOME_NAME"
Write-Verbose "resolved distributionUrl: $distributionUrl"
Write-Verbose "wrapper cache directory: $MAVEN_HOME"

if (Test-Path -Path "$MAVEN_HOME" -PathType Container) {
  Write-Verbose "found existing MAVEN_HOME at $MAVEN_HOME"
  Write-Output "MVN_CMD=$MAVEN_HOME/bin/$MVN_CMD"
  exit $?
}

if (! $distributionUrlNameMain -or ($distributionUrlName -eq $distributionUrlNameMain)) {
  Write-Error "distributionUrl is not valid, must end with *-bin.zip, but found $distributionUrl"
}

# prepare tmp dir
$TMP_DOWNLOAD_DIR_HOLDER = New-TemporaryFile
$TMP_DOWNLOAD_DIR = New-Item -Itemtype Directory -Path "$TMP_DOWNLOAD_DIR_HOLDER.dir"
$TMP_DOWNLOAD_DIR_HOLDER.Delete() | Out-Null
trap {
  if ($TMP_DOWNLOAD_DIR.Exists) {
    try { Remove-Item $TMP_DOWNLOAD_DIR -Recurse -Force | Out-Null }
    catch { Write-Warning "Cannot remove $TMP_DOWNLOAD_DIR" }
  }
}

New-Item -Itemtype Directory -Path "$MAVEN_HOME_PARENT" -Force | Out-Null

# Download and Install Apache Maven
Write-Verbose "Couldn't find MAVEN_HOME, downloading and installing it ..."
Write-Verbose "Downloading from: $distributionUrl"
Write-Verbose "Downloading to: $TMP_DOWNLOAD_DIR/$distributionUrlName"

$webclient = New-Object System.Net.WebClient
if ($env:MVNW_USERNAME -and $env:MVNW_PASSWORD) {
  $webclient.Credentials = New-Object System.Net.NetworkCredential($env:MVNW_USERNAME, $env:MVNW_PASSWORD)
}
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$webclient.DownloadFile($distributionUrl, "$TMP_DOWNLOAD_DIR/$distributionUrlName") | Out-Null

# If specified, validate the SHA-256 sum of the Maven distribution zip file
$distributionSha256Sum = (Get-Content -Raw -LiteralPath "$scriptDir/.mvn/wrapper/maven-wrapper.properties" | ConvertFrom-StringData).distributionSha256Sum
if ($distributionSha256Sum) {
  Write-Verbose "verifying the download against distributionSha256Sum from the properties file"
} else {
  Write-Verbose "distributionSha256Sum is absent, the downloaded distribution will NOT be verified"
}
if ($distributionSha256Sum) {
  if ($USE_MVND) {
    Write-Error "Checksum validation is not supported for maven-mvnd. `nPlease disable validation by removing 'distributionSha256Sum' from your maven-wrapper.properties."
  }
  Import-Module $PSHOME\Modules\Microsoft.PowerShell.Utility -Function Get-FileHash
  if ((Get-FileHash "$TMP_DOWNLOAD_DIR/$distributionUrlName" -Algorithm SHA256).Hash.ToLower() -ne $distributionSha256Sum) {
    Write-Error "Error: Failed to validate Maven distribution SHA-256, your Maven distribution might be compromised. If you updated your Maven version, you need to update the specified distributionSha256Sum property."
  }
}

# unzip and move
Expand-Archive "$TMP_DOWNLOAD_DIR/$distributionUrlName" -DestinationPath "$TMP_DOWNLOAD_DIR" | Out-Null

# Find the actual extracted directory name (handles snapshots where filename != directory name)
$actualDistributionDir = ""

# First try the expected directory name (for regular distributions)
$expectedPath = Join-Path "$TMP_DOWNLOAD_DIR" "$distributionUrlNameMain"
$expectedMvnPath = Join-Path "$expectedPath" "bin/$MVN_CMD"
if ((Test-Path -Path $expectedPath -PathType Container) -and (Test-Path -Path $expectedMvnPath -PathType Leaf)) {
  $actualDistributionDir = $distributionUrlNameMain
}

# If not found, search for any directory with the Maven executable (for snapshots)
if (!$actualDistributionDir) {
  Get-ChildItem -Path "$TMP_DOWNLOAD_DIR" -Directory | ForEach-Object {
    $testPath = Join-Path $_.FullName "bin/$MVN_CMD"
    if (Test-Path -Path $testPath -PathType Leaf) {
      $actualDistributionDir = $_.Name
    }
  }
}

if (!$actualDistributionDir) {
  Write-Error "Could not find Maven distribution directory in extracted archive"
}

Write-Verbose "Found extracted Maven distribution directory: $actualDistributionDir"
Rename-Item -Path "$TMP_DOWNLOAD_DIR/$actualDistributionDir" -NewName $MAVEN_HOME_NAME | Out-Null
try {
  Move-Item -Path "$TMP_DOWNLOAD_DIR/$MAVEN_HOME_NAME" -Destination $MAVEN_HOME_PARENT | Out-Null
} catch {
  if (! (Test-Path -Path "$MAVEN_HOME" -PathType Container)) {
    Write-Error "fail to move MAVEN_HOME"
  }
} finally {
  try { Remove-Item $TMP_DOWNLOAD_DIR -Recurse -Force | Out-Null }
  catch { Write-Warning "Cannot remove $TMP_DOWNLOAD_DIR" }
}

Write-Output "MVN_CMD=$MAVEN_HOME/bin/$MVN_CMD"
