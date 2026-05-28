# ==============================================================================
#  VATN Installer — Windows (PowerShell 5.1 / 7+)
#
#  Run from an elevated PowerShell prompt:
#    Set-ExecutionPolicy Bypass -Scope Process -Force
#    irm https://raw.githubusercontent.com/RainerXE/vatn/main/install.ps1 | iex
#
#  Or download and run:
#    Invoke-WebRequest https://raw.githubusercontent.com/RainerXE/vatn/main/install.ps1 -OutFile install.ps1
#    .\install.ps1
#
#  Env-var overrides:
#    VATN_INSTALL_DIR   — target directory   (default: %USERPROFILE%\.vatn)
#    VATN_JAVA          — "graal" | "graalce" | "skip"
#    VATN_PLUGINS       — comma-list of plugin names, "all", or "recommended"
# ==============================================================================
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

# ── constants ─────────────────────────────────────────────────────────────────
$VatnOrg          = 'RainerXE'
$VatnCoreRepo     = 'vatn'
$VatnPluginsRepo  = 'vatn-plugins'
$MinJavaMajor     = 21
$DefaultInstall   = Join-Path $env:USERPROFILE '.vatn'

# ── colour helpers ────────────────────────────────────────────────────────────
function Write-Info    { Write-Host "  > $args" -ForegroundColor Cyan }
function Write-Ok      { Write-Host "  v $args" -ForegroundColor Green }
function Write-Warn    { Write-Host "  ! $args" -ForegroundColor Yellow }
function Write-Err     { Write-Host "  x $args" -ForegroundColor Red }
function Write-Step    { Write-Host "`n== $args ==" -ForegroundColor Blue }
function Write-Banner  {
    $lines = @(
        "  +------------------------------------------------------+",
        "  |                                                      |",
        "  |   VATN -- Federated OS for Personal AI              |",
        "  |   Installer v1.0                                     |",
        "  |                                                      |",
        "  +------------------------------------------------------+"
    )
    Write-Host ""
    $lines | ForEach-Object { Write-Host $_ -ForegroundColor Blue }
    Write-Host ""
}

function Invoke-Prompt {
    param([string]$Message, [string]$Default = '')
    $hint = if ($Default) { " [$Default]" } else { '' }
    Write-Host "  ? $Message$hint " -NoNewline -ForegroundColor Cyan
    $answer = Read-Host
    if ([string]::IsNullOrWhiteSpace($answer)) { $Default } else { $answer }
}

function Test-Command { param([string]$Name); $null -ne (Get-Command $Name -ErrorAction SilentlyContinue) }

function Get-GithubLatestTag {
    param([string]$Repo)
    try {
        $resp = Invoke-RestMethod "https://api.github.com/repos/$Repo/releases/latest" -ErrorAction SilentlyContinue
        return $resp.tag_name
    } catch { return 'latest' }
}

function Invoke-Download {
    param([string]$Url, [string]$Dest, [string]$Label)
    Write-Info "Downloading $Label..."
    try {
        $wc = New-Object System.Net.WebClient
        $wc.DownloadFile($Url, $Dest)
        Write-Ok $Label
        return $true
    } catch {
        Write-Warn "Failed: $Label — $($_.Exception.Message)"
        if (Test-Path $Dest) { Remove-Item $Dest -Force }
        return $false
    }
}

# ── banner ────────────────────────────────────────────────────────────────────
Write-Banner

# ── OS check ─────────────────────────────────────────────────────────────────
Write-Step "Checking environment"

if (-not $IsWindows -and $PSVersionTable.PSVersion.Major -ge 6) {
    Write-Err "This script is for Windows. Use install.sh on Linux/macOS."
    exit 1
}
Write-Ok "Windows detected (PowerShell $($PSVersionTable.PSVersion))"

# ── Java detection ────────────────────────────────────────────────────────────
Write-Step "Java"

$JavaOk = $false
$CurrentJava = ''

if (Test-Command 'java') {
    $javaVerLine = (java -version 2>&1)[0].ToString()
    if ($javaVerLine -match '(\d+)') {
        $major = [int]$matches[1]
        if ($major -ge $MinJavaMajor) {
            $JavaOk = $true
            $CurrentJava = $javaVerLine
            Write-Ok "Compatible Java: $CurrentJava"
        } else {
            Write-Warn "Java $major found — VATN requires Java $MinJavaMajor+."
        }
    }
} else {
    Write-Warn "No Java found on PATH."
}

# ── GraalVM choice ────────────────────────────────────────────────────────────
$InstallJava   = $false
$GraalVariant  = ''

$EnvJava = $env:VATN_JAVA
if ($EnvJava) {
    switch ($EnvJava.ToLower()) {
        'graal'   { $InstallJava = $true; $GraalVariant = 'graal'   }
        'graalce' { $InstallJava = $true; $GraalVariant = 'graalce' }
        'skip'    { Write-Info "Skipping Java (VATN_JAVA=skip)." }
        default   { Write-Warn "Unknown VATN_JAVA value '$EnvJava' — skipping." }
    }
} elseif (-not $JavaOk) {
    Write-Host ""
    Write-Host "  VATN recommends GraalVM for AOT compilation and best performance." -ForegroundColor Cyan
    Write-Host ""
    Write-Host "   1) Oracle GraalVM   — GraalVM + JVMCI, free for development" -ForegroundColor Green
    Write-Host "   2) GraalVM CE       — community edition, Apache 2.0"          -ForegroundColor Cyan
    Write-Host "   3) Skip             — configure Java manually"                 -ForegroundColor DarkGray
    Write-Host ""
    $choice = Invoke-Prompt "Choose [1/2/3]" "1"
    switch ($choice) {
        '1' { $InstallJava = $true; $GraalVariant = 'graal'   }
        '2' { $InstallJava = $true; $GraalVariant = 'graalce' }
        default { Write-Warn "Skipping Java install. Ensure Java $MinJavaMajor+ is on PATH." }
    }
} else {
    Write-Host ""
    Write-Host "  Upgrade to GraalVM? (enables native AOT compilation)" -ForegroundColor White
    Write-Host "   1) Oracle GraalVM   (recommended)"                   -ForegroundColor Green
    Write-Host "   2) GraalVM CE       (Apache 2.0)"                    -ForegroundColor Cyan
    Write-Host "   3) Keep current Java"                                -ForegroundColor DarkGray
    Write-Host ""
    $choice = Invoke-Prompt "Choose [1/2/3]" "3"
    switch ($choice) {
        '1' { $InstallJava = $true; $GraalVariant = 'graal'   }
        '2' { $InstallJava = $true; $GraalVariant = 'graalce' }
        default { Write-Info "Keeping existing Java." }
    }
}

# ── Install GraalVM ───────────────────────────────────────────────────────────
if ($InstallJava) {
    Write-Step "Installing GraalVM"

    $installed = $false

    # 1. Try winget (available on Windows 11 and updated Windows 10)
    if (Test-Command 'winget') {
        $wingetId = if ($GraalVariant -eq 'graal') { 'Oracle.GraalVM' } else { 'GraalVM.GraalVM.CE' }
        Write-Info "Trying winget ($wingetId)..."
        try {
            winget install --id $wingetId --accept-source-agreements --accept-package-agreements
            $installed = $true
            Write-Ok "GraalVM installed via winget"
        } catch {
            Write-Warn "winget install failed — trying alternative methods."
        }
    }

    # 2. Try Chocolatey
    if (-not $installed -and (Test-Command 'choco')) {
        $chocoId = if ($GraalVariant -eq 'graal') { 'graalvm' } else { 'graalvm-community' }
        Write-Info "Trying Chocolatey ($chocoId)..."
        try {
            choco install $chocoId -y
            $installed = $true
            Write-Ok "GraalVM installed via Chocolatey"
        } catch {
            Write-Warn "Chocolatey install failed."
        }
    }

    # 3. Try Scoop
    if (-not $installed -and (Test-Command 'scoop')) {
        Write-Info "Trying Scoop..."
        try {
            scoop bucket add java
            $scoopId = if ($GraalVariant -eq 'graal') { 'graalvm-oracle' } else { 'graalvm' }
            scoop install $scoopId
            $installed = $true
            Write-Ok "GraalVM installed via Scoop"
        } catch {
            Write-Warn "Scoop install failed."
        }
    }

    # 4. Direct download fallback
    if (-not $installed) {
        Write-Warn "No package manager succeeded. Attempting direct download..."
        $jdkVersion = '25'
        $arch = if ([System.Environment]::Is64BitOperatingSystem) { 'x64' } else { 'x86' }

        if ($GraalVariant -eq 'graal') {
            # Oracle GraalVM download from oracle.com
            $oracleUrl = "https://download.oracle.com/graalvm/$jdkVersion/latest/graalvm-jdk-${jdkVersion}_windows-${arch}_bin.zip"
            $zipPath   = Join-Path $env:TEMP "graalvm.zip"
            $jvmDir    = Join-Path $env:LOCALAPPDATA "GraalVM"
            if (Invoke-Download -Url $oracleUrl -Dest $zipPath -Label "GraalVM JDK $jdkVersion") {
                Write-Info "Extracting GraalVM..."
                Expand-Archive -Path $zipPath -DestinationPath $jvmDir -Force
                Remove-Item $zipPath -Force
                $jdkDir = Get-ChildItem $jvmDir -Directory | Select-Object -First 1
                $env:JAVA_HOME = $jdkDir.FullName
                $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
                [System.Environment]::SetEnvironmentVariable('JAVA_HOME', $env:JAVA_HOME, 'User')
                [System.Environment]::SetEnvironmentVariable('PATH',
                    "$env:JAVA_HOME\bin;" + [System.Environment]::GetEnvironmentVariable('PATH','User'), 'User')
                $installed = $true
                Write-Ok "Oracle GraalVM extracted to $($jdkDir.FullName)"
            }
        } else {
            # GraalVM CE from GitHub releases
            $ceUrl   = "https://github.com/graalvm/graalvm-ce-builds/releases/latest/download/graalvm-community-jdk-${jdkVersion}_windows-${arch}_bin.zip"
            $zipPath = Join-Path $env:TEMP "graalvm-ce.zip"
            $jvmDir  = Join-Path $env:LOCALAPPDATA "GraalVM-CE"
            if (Invoke-Download -Url $ceUrl -Dest $zipPath -Label "GraalVM CE JDK $jdkVersion") {
                Write-Info "Extracting GraalVM CE..."
                Expand-Archive -Path $zipPath -DestinationPath $jvmDir -Force
                Remove-Item $zipPath -Force
                $jdkDir = Get-ChildItem $jvmDir -Directory | Select-Object -First 1
                $env:JAVA_HOME = $jdkDir.FullName
                $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
                [System.Environment]::SetEnvironmentVariable('JAVA_HOME', $env:JAVA_HOME, 'User')
                [System.Environment]::SetEnvironmentVariable('PATH',
                    "$env:JAVA_HOME\bin;" + [System.Environment]::GetEnvironmentVariable('PATH','User'), 'User')
                $installed = $true
                Write-Ok "GraalVM CE extracted to $($jdkDir.FullName)"
            }
        }
    }

    if (-not $installed) {
        Write-Warn @"
Automatic installation failed. Please install GraalVM manually:
  Oracle: https://www.graalvm.org/downloads/
  CE:     https://github.com/graalvm/graalvm-ce-builds/releases
Then re-run this installer.
"@
    }
}

# verify
if (-not (Test-Command 'java')) {
    Write-Err "java not found on PATH. Install Java $MinJavaMajor+ and re-run."
    exit 1
}
Write-Ok "Using: $((java -version 2>&1)[0])"

# ── Installation directory ────────────────────────────────────────────────────
Write-Step "Installation directory"

$envDir     = $env:VATN_INSTALL_DIR
$defaultDir = if ($envDir) { $envDir } else { $DefaultInstall }
Write-Host ""

$InstallDir = Invoke-Prompt "Install location" $defaultDir
if (-not $InstallDir) { $InstallDir = $defaultDir }

if ((Test-Path "$InstallDir\lib") -or (Test-Path "$InstallDir\plugins")) {
    Write-Warn "Existing installation found at $InstallDir"
    $upgrade = Invoke-Prompt "Upgrade in place? [Y/n]" "Y"
    if ($upgrade -match '^[Nn]') { Write-Host "Installation cancelled."; exit 0 }
}

@('bin','lib','plugins','config','logs') | ForEach-Object {
    $d = Join-Path $InstallDir $_
    if (-not (Test-Path $d)) { New-Item -ItemType Directory -Path $d | Out-Null }
}
Write-Ok "Directory layout ready: $InstallDir"

# ── Download VATN core ────────────────────────────────────────────────────────
Write-Step "Downloading VATN runtime"

$coreTag = Get-GithubLatestTag "$VatnOrg/$VatnCoreRepo"
Write-Info "Release tag: $coreTag"

$coreUrl  = "https://github.com/$VatnOrg/$VatnCoreRepo/releases/download/$coreTag/vatn-cli.jar"
$coreJar  = Join-Path $InstallDir 'lib\vatn-cli.jar'
$coreOk   = Invoke-Download -Url $coreUrl -Dest $coreJar -Label "vatn-cli.jar"
if (-not $coreOk) {
    Write-Warn "No release available yet. Copy vatn-cli.jar to $coreJar after building from source."
    "# placeholder" | Out-File "$coreJar.placeholder"
}

# ── Plugin selection ──────────────────────────────────────────────────────────
Write-Step "Plugin selection"

$PluginDefs = @(
    @{ Name='cors';        Desc='CORS filter for browser-accessible APIs';             Default=$true  }
    @{ Name='auth';        Desc='JWT + API-key authentication';                        Default=$true  }
    @{ Name='swagger';     Desc='OpenAPI / Swagger UI at /api/docs';                   Default=$true  }
    @{ Name='security';    Desc='CSRF, rate limiting, security headers';               Default=$false }
    @{ Name='bcrypt';      Desc='BCrypt password hashing';                             Default=$false }
    @{ Name='postgres';    Desc='PostgreSQL connection pool (HikariCP)';               Default=$false }
    @{ Name='redis';       Desc='Redis client (Jedis)';                                Default=$false }
    @{ Name='mongodb';     Desc='MongoDB driver integration';                          Default=$false }
    @{ Name='openai';      Desc='OpenAI / LLM client';                                Default=$false }
    @{ Name='metrics';     Desc='Prometheus /metrics endpoint';                        Default=$false }
    @{ Name='email';       Desc='SMTP email via Jakarta Mail';                         Default=$false }
    @{ Name='slack';       Desc='Slack webhook + Events API';                          Default=$false }
    @{ Name='s3';          Desc='AWS S3 / compatible object storage';                  Default=$false }
    @{ Name='comm';        Desc='Communication hub: Telegram, Signal, RCS';            Default=$false }
    @{ Name='indexer';     Desc='Full-text search indexing';                           Default=$false }
    @{ Name='scraper';     Desc='Headless web scraping';                               Default=$false }
    @{ Name='activitypub'; Desc='ActivityPub / Fediverse federation';                  Default=$false }
)

$EnvPlugins = $env:VATN_PLUGINS

if (-not $EnvPlugins) {
    Write-Host ""
    Write-Host "  Select plugins to install:" -ForegroundColor White
    Write-Host "  admin is always included.  [*] = recommended default." -ForegroundColor DarkGray
    Write-Host ""
    Write-Host ("  {0,-6}  {1,-24}  {2}" -f '[always]', 'vatn-plugin-admin', 'Admin dashboard UI + JVM metrics + plugin management') -ForegroundColor Green
    Write-Host ""

    $idx = 1
    foreach ($p in $PluginDefs) {
        $marker = if ($p.Default) { '[*]  ' } else { '     ' }
        $color  = if ($p.Default) { 'Yellow' } else { 'Gray' }
        Write-Host ("  {0}{1,2})  {2,-24}  {3}" -f $marker, $idx, "vatn-plugin-$($p.Name)", $p.Desc) -ForegroundColor $color
        $idx++
    }

    Write-Host ""
    Write-Host "  Enter numbers (e.g. 1 3 6), 'recommended', or 'all'.  Default: recommended" -ForegroundColor DarkGray
    $EnvPlugins = Invoke-Prompt "Your selection" "recommended"
}

$SelectedPlugins = [System.Collections.Generic.List[string]]::new()
$SelectedPlugins.Add('admin')

switch ($EnvPlugins.ToLower().Trim()) {
    'all' {
        $PluginDefs | ForEach-Object { $SelectedPlugins.Add($_.Name) }
    }
    { $_ -eq 'recommended' -or [string]::IsNullOrWhiteSpace($_) } {
        $PluginDefs | Where-Object { $_.Default } | ForEach-Object { $SelectedPlugins.Add($_.Name) }
    }
    default {
        $tokens = $EnvPlugins -split '[,\s]+' | Where-Object { $_ }
        $i = 1
        foreach ($p in $PluginDefs) {
            foreach ($t in $tokens) {
                if ($t -eq "$i" -or $t -eq $p.Name) {
                    $SelectedPlugins.Add($p.Name)
                    break
                }
            }
            $i++
        }
    }
}

# ── Download plugins ──────────────────────────────────────────────────────────
Write-Step "Downloading plugins"

$pluginTag = Get-GithubLatestTag "$VatnOrg/$VatnPluginsRepo"
$FailedPlugins = @()

foreach ($plugin in $SelectedPlugins) {
    $jar  = "vatn-plugin-$plugin.jar"
    $url  = "https://github.com/$VatnOrg/$VatnPluginsRepo/releases/download/$pluginTag/$jar"
    $dest = Join-Path $InstallDir "plugins\$jar"
    $ok   = Invoke-Download -Url $url -Dest $dest -Label $jar
    if (-not $ok) { $FailedPlugins += $plugin }
}

# ── Default configuration ─────────────────────────────────────────────────────
Write-Step "Configuration"

$confPath = Join-Path $InstallDir 'config\vatn.conf'
if (-not (Test-Path $confPath)) {
    @'
# VATN Node configuration
# Docs: https://github.com/RainerXE/vatn/blob/main/docs/configuration.md

# vatn.nodeId=my-node-1   # auto-generated if omitted
vatn.port=8080
vatn.host=0.0.0.0

# Admin UI — set VATN_ADMIN_TOKEN env var or uncomment:
# vatn.admin.token=change-me

vatn.log.level=INFO
'@ | Out-File -FilePath $confPath -Encoding UTF8
    Write-Ok "Config created: $confPath"
} else {
    Write-Info "Existing config preserved: $confPath"
}

# ── Launcher scripts ──────────────────────────────────────────────────────────
Write-Step "Launcher"

# .bat wrapper for cmd.exe / old-school Windows
$batPath = Join-Path $InstallDir 'bin\vatn.bat'
@"
@echo off
set VATN_HOME=%~dp0..
set VATN_JAR=%VATN_HOME%\lib\vatn-cli.jar
set VATN_PLUGINS=%VATN_HOME%\plugins
set VATN_CONF=%VATN_HOME%\config\vatn.conf

if not exist "%VATN_JAR%" (
  echo vatn-cli.jar not found at %VATN_JAR% 1>&2
  exit /b 1
)

set CP=%VATN_JAR%
for %%f in ("%VATN_PLUGINS%\*.jar") do set CP=!CP!;%%f

java -cp "%CP%" ^
  -Dvatn.home="%VATN_HOME%" ^
  -Dvatn.config="%VATN_CONF%" ^
  -Dvatn.plugins.dir="%VATN_PLUGINS%" ^
  dev.vatn.cli.VatnCLI %*
"@ | Out-File -FilePath $batPath -Encoding ASCII
Write-Ok "Launcher (cmd):        $batPath"

# .ps1 wrapper for PowerShell
$ps1Path = Join-Path $InstallDir 'bin\vatn.ps1'
@'
param([Parameter(ValueFromRemainingArguments=$true)]$Args)
$Home_ = Split-Path $PSScriptRoot -Parent
$Jar   = Join-Path $Home_ 'lib\vatn-cli.jar'
$Plug  = Join-Path $Home_ 'plugins'
$Conf  = Join-Path $Home_ 'config\vatn.conf'
if (-not (Test-Path $Jar)) { Write-Error "vatn-cli.jar not found at $Jar"; exit 1 }
$cp = $Jar
Get-ChildItem $Plug -Filter '*.jar' | ForEach-Object { $cp += ";$($_.FullName)" }
& java -cp $cp "-Dvatn.home=$Home_" "-Dvatn.config=$Conf" "-Dvatn.plugins.dir=$Plug" dev.vatn.cli.VatnCLI @Args
'@ | Out-File -FilePath $ps1Path -Encoding UTF8
Write-Ok "Launcher (PowerShell): $ps1Path"

# ── PATH setup ────────────────────────────────────────────────────────────────
Write-Step "PATH"

$binDir      = Join-Path $InstallDir 'bin'
$currentPath = [System.Environment]::GetEnvironmentVariable('PATH', 'User')
if ($currentPath -notlike "*$binDir*") {
    [System.Environment]::SetEnvironmentVariable('PATH', "$binDir;$currentPath", 'User')
    $env:PATH = "$binDir;$env:PATH"
    Write-Ok "Added to user PATH: $binDir"
} else {
    Write-Info "Already in PATH: $binDir"
}

# ── Developer setup (optional) ───────────────────────────────────────────────
Write-Step "Developer setup (optional)"

Write-Host ""
Write-Host "  Clone the VATN source repos to build custom plugins,"
Write-Host "  contribute to the runtime, or explore the codebase."
Write-Host "  Requires: git, Maven 3.9+"
Write-Host ""

$doClone = Invoke-Prompt "Clone source repos for local development? [y/N]" "N"
if ($doClone -match '^[Yy]') {

    if (-not (Test-Command 'git')) {
        Write-Warn "git not found — skipping clone. Install Git for Windows and re-run."
        Write-Host "  Then run:" -ForegroundColor DarkGray
        Write-Host "    git clone https://github.com/$VatnOrg/$VatnCoreRepo.git" -ForegroundColor Cyan
        Write-Host "    git clone https://github.com/$VatnOrg/$VatnPluginsRepo.git" -ForegroundColor Cyan
    } else {
        # detect sensible default dev directory
        $devCandidates = @(
            (Join-Path $env:USERPROFILE 'Development'),
            (Join-Path $env:USERPROFILE 'Projects'),
            (Join-Path $env:USERPROFILE 'dev'),
            (Join-Path $env:USERPROFILE 'code'),
            (Join-Path $env:USERPROFILE 'source')
        )
        $defaultDev = $devCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
        if (-not $defaultDev) { $defaultDev = Join-Path $env:USERPROFILE 'Development' }

        $devDir = Invoke-Prompt "Development directory" $defaultDev
        if (-not $devDir) { $devDir = $defaultDev }
        if (-not (Test-Path $devDir)) { New-Item -ItemType Directory -Path $devDir | Out-Null }

        foreach ($repo in @($VatnCoreRepo, $VatnPluginsRepo)) {
            $target = Join-Path $devDir $repo
            if (Test-Path "$target\.git") {
                Write-Info "$repo already cloned — pulling latest..."
                try { git -C $target pull --ff-only 2>&1 | Out-Null; Write-Ok "$repo up to date" }
                catch { Write-Warn "Could not update $repo" }
            } else {
                Write-Info "Cloning $repo..."
                try {
                    git clone "https://github.com/$VatnOrg/$repo.git" $target
                    Write-Ok "$repo -> $target"
                } catch { Write-Warn "Clone failed: $repo" }
            }
        }

        Write-Host ""
        $doDemo = Invoke-Prompt "Also clone vatn-demo (example ports and tutorials)? [y/N]" "N"
        if ($doDemo -match '^[Yy]') {
            $demoTarget = Join-Path $devDir 'vatn-demo'
            if (Test-Path "$demoTarget\.git") {
                try { git -C $demoTarget pull --ff-only 2>&1 | Out-Null } catch {}
                Write-Info "vatn-demo already present"
            } else {
                try {
                    git clone "https://github.com/$VatnOrg/vatn-demo.git" $demoTarget
                    Write-Ok "vatn-demo -> $demoTarget"
                } catch { Write-Warn "Clone failed: vatn-demo" }
            }
        }

        Write-Host ""
        Write-Ok "Source repos ready in $devDir"
        Write-Host ""
        Write-Host "  Build the runtime:" -ForegroundColor White
        Write-Host "    cd $devDir\$VatnCoreRepo" -ForegroundColor Cyan
        Write-Host "    mvn clean install -DskipTests   # builds api + core + cli" -ForegroundColor Cyan
        Write-Host ""
        Write-Host "  Build plugins:" -ForegroundColor White
        Write-Host "    cd $devDir\$VatnPluginsRepo" -ForegroundColor Cyan
        Write-Host "    mvn clean install -DskipTests" -ForegroundColor Cyan
        Write-Host ""
        Write-Host "  Deploy your build to the local VATN installation:" -ForegroundColor White
        Write-Host "    copy $devDir\$VatnCoreRepo\vatn-cli\target\vatn-cli-*.jar $InstallDir\lib\vatn-cli.jar" -ForegroundColor Cyan
        Write-Host "    copy $devDir\$VatnPluginsRepo\vatn-plugin-*\target\vatn-plugin-*.jar $InstallDir\plugins\" -ForegroundColor Cyan
    }
}

# ── Summary ───────────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "  +------------------------------------------------------+" -ForegroundColor Green
Write-Host "  |   v  VATN installed successfully!                   |" -ForegroundColor Green
Write-Host "  +------------------------------------------------------+" -ForegroundColor Green
Write-Host ""
Write-Host "  Home:     $InstallDir"
Write-Host "  Config:   $confPath"
Write-Host "  Plugins:  $($SelectedPlugins.Count) installed"
Write-Host ""

if ($FailedPlugins.Count -gt 0) {
    Write-Warn "Plugins not yet released (copy JARs to $InstallDir\plugins\ once built):"
    $FailedPlugins | ForEach-Object { Write-Host "    - vatn-plugin-$_" -ForegroundColor DarkGray }
    Write-Host ""
}

Write-Host "  Next steps:" -ForegroundColor White
Write-Host "  1.  Reload PATH:       Open a new terminal window"
Write-Host "  2.  Verify install:    vatn --version"
Write-Host "  3.  Create a project:  vatn init my-project"
Write-Host "  4.  Start a node:      vatn run"
Write-Host ""
Write-Host "  Docs: https://github.com/RainerXE/vatn/blob/main/README.md" -ForegroundColor Cyan
Write-Host ""
