<#
.SYNOPSIS
  Builds the Nurgling2 (Haven & Hearth) client inside a disposable Docker container.

.DESCRIPTION
  Spins up a throwaway Linux container with JDK 17 + Apache Ant - the same
  toolchain the project's own CI uses (.github/workflows/build-pr.yml) - and
  bind-mounts your repo checkout into it to run the Ant build. Because the repo
  is mounted rather than copied, every output (build/, bin/, lib/ext/) is written
  straight back to your local checkout, so lib/ext (the jogl/lwjgl/steamworks
  jars Ant downloads) is cached across runs instead of being re-fetched each time.

  Requires Docker Desktop to be installed and running, and outbound internet
  access to www.havenandhearth.com (Ant fetches the native-library jars from
  there on first build).

.PARAMETER Target
  Ant target to run. Left empty, this runs Ant's default target ("deftgt"),
  which produces the full runnable client under bin\ (hafen.jar plus every
  native/resource jar it needs). Pass "jar" for a quick compile-only check
  (what the PR-check CI job runs) producing build\hafen.jar, "release" for a
  full release build, "clean" to wipe build artifacts, etc.

.PARAMETER RepoPath
  Path to your local nurgling2 checkout. Defaults to the parent of the folder
  this script lives in, i.e. it "just works" when run as
  <repo>\docker\build.ps1. Pass this explicitly if you copied the script
  elsewhere.

.EXAMPLE
  .\docker\build.ps1
  Full client build. Output: .\bin\hafen.jar (+ all its dependency jars).

.EXAMPLE
  .\docker\build.ps1 -Target jar
  Quick compile-only build. Output: .\build\hafen.jar.

.EXAMPLE
  .\docker\build.ps1 -RepoPath C:\Users\alexa\Documents\GitHub\nurgling2
  Explicit repo path, e.g. when running the script from somewhere else.
#>
param(
    [string]$Target = "",
    [string]$RepoPath = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = "Stop"

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Error "Docker CLI not found. Install/start Docker Desktop first: https://www.docker.com/products/docker-desktop/"
    exit 1
}

if (-not (Test-Path (Join-Path $RepoPath "build.xml"))) {
    Write-Error "build.xml not found under '$RepoPath'. Pass -RepoPath pointing at your nurgling2 checkout."
    exit 1
}

$image = "nurgling2-builder"

Write-Host "==> Building builder image ($image)..." -ForegroundColor Cyan
$buildArgs = @("build", "-t", $image, "-f", (Join-Path $PSScriptRoot "Dockerfile"), $PSScriptRoot)
& docker @buildArgs
if ($LASTEXITCODE -ne 0) {
    Write-Error "docker build failed (exit $LASTEXITCODE)."
    exit $LASTEXITCODE
}

Write-Host "==> Running Ant build against $RepoPath ..." -ForegroundColor Cyan
$runArgs = @(
    "run", "--rm",
    "-v", "${RepoPath}:/workspace",
    "-w", "/workspace",
    $image,
    "ant"
)
if ($Target -ne "") {
    $runArgs += $Target
}
& docker @runArgs
if ($LASTEXITCODE -ne 0) {
    Write-Error "Build failed (exit $LASTEXITCODE)."
    exit $LASTEXITCODE
}

Write-Host "==> Build succeeded." -ForegroundColor Green
if ($Target -eq "") {
    Write-Host "Runnable client is under: $RepoPath\bin"
} else {
    Write-Host "Output is under: $RepoPath\build"
}
