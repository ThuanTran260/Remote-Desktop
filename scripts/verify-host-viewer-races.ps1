#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$EvidenceDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Install as scripts/verify-host-viewer-races.ps1. There is no selector override.
$RaceSelectors = @(
    'com.ltm.rd.host.HostSessionProtocolEdgesTest#closeRacingPrepareStartSkipsOrJoinsWorker'
    'com.ltm.rd.host.HostSessionProtocolEdgesTest#reserveDeadlineRacingPrepareDiscardsExpiredResult'
    'com.ltm.rd.host.HostSessionProtocolEdgesTest#attachRacingPrepareActivatesExactlyOnce'
    'com.ltm.rd.it.ClientServerIntegrationTest#cancelRacingAuthResultNeverResurrectsViewer'
    'com.ltm.rd.it.ClientServerIntegrationTest#imageLossRacingActivationClosesBothPeers'
    'com.ltm.rd.viewer.ViewerSessionControlTest#stallRacingInputNeverQueuesKeyAfterReleaseAll'
    'com.ltm.rd.viewer.ViewerSessionGateTest#disconnectRacingFrameNeverReopensGate'
    'com.ltm.rd.host.SlotReleaseIntegrationTest#authRacingEndSessionKeepsSlotUntilRelease'
)

function Join-FilePath([string]$Parent, [string]$Child) {
    return [IO.Path]::Combine($Parent, $Child.Replace('/', '\'))
}

function Write-NewText([string]$Path, [string]$Text) {
    $stream = [IO.File]::Open($Path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::Read)
    try {
        $bytes = (New-Object Text.UTF8Encoding($false)).GetBytes($Text)
        $stream.Write($bytes, 0, $bytes.Length)
    } finally { $stream.Dispose() }
}

function Write-NewJson([string]$Path, $Value) {
    Write-NewText $Path ($Value | ConvertTo-Json -Depth 12)
}

function Get-ExtendedPath([string]$Path) {
    $full = [IO.Path]::GetFullPath($Path)
    if ($full.StartsWith('\\?\')) { return $full }
    if ($full.StartsWith('\\')) { return '\\?\UNC\' + $full.Substring(2) }
    return '\\?\' + $full
}

function Assert-NoReparse([string]$Path) {
    $cursor = [IO.Path]::GetFullPath($Path)
    while ($cursor) {
        if (Test-Path -LiteralPath $cursor) {
            $item = Get-Item -LiteralPath $cursor -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Reparse points are not allowed: $cursor"
            }
        }
        $parent = [IO.Path]::GetDirectoryName($cursor)
        if ($parent -eq $cursor) { break }
        $cursor = $parent
    }
}

function Test-Within([string]$Path, [string]$Parent) {
    $p = [IO.Path]::GetFullPath($Path).TrimEnd('\', '/')
    $d = [IO.Path]::GetFullPath($Parent).TrimEnd('\', '/')
    return $p.Equals($d, [StringComparison]::OrdinalIgnoreCase) -or
        $p.StartsWith($d + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)
}

function Assert-EvidenceLocation([string]$Path, [string[]]$ProtectedPaths) {
    if ($Path -notmatch '^(?:[A-Za-z]:[\\/]|\\\\[^\\/]+[\\/][^\\/]+(?:[\\/]|$))' -or
        $Path.StartsWith('\\?\') -or $Path.StartsWith('\\.\')) {
        throw 'EvidenceDirectory must be an absolute path.'
    }
    Assert-NoReparse $Path
    foreach ($protected in $ProtectedPaths) {
        if ((Test-Within $Path $protected) -or (Test-Within $protected $Path)) {
            throw "EvidenceDirectory overlaps an input: $protected"
        }
    }
    if ((Test-Path -LiteralPath $Path) -and -not (Test-Path -LiteralPath $Path -PathType Container)) {
        throw 'EvidenceDirectory is not a directory.'
    }
}

function Get-SafeFiles([string]$Directory) {
    Assert-NoReparse $Directory
    if (-not (Test-Path -LiteralPath $Directory -PathType Container)) { throw "Missing directory: $Directory" }
    foreach ($item in Get-ChildItem -LiteralPath $Directory -Force) {
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "Reparse point: $($item.FullName)" }
        if ($item.PSIsContainer) { Get-SafeFiles $item.FullName } else { $item.FullName }
    }
}

function Get-TextHash([string]$Text) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Text)))).Replace('-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
}

function Invoke-NativeCapture([string]$File, [string[]]$Arguments) {
    # PS 5.1 turns stderr into ErrorRecord objects. Only this native capture uses
    # Continue; all file operations and the caller retain terminating errors.
    $savedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $global:LASTEXITCODE = $null
        $lines = @(& $File @Arguments 2>&1 | ForEach-Object { $_.ToString() })
        $code = $global:LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    if ($null -eq $code) { throw "Native command did not produce an exit status: $File" }
    return [pscustomobject]@{ ExitCode = [int]$code; Lines = $lines }
}

function Invoke-NativeLogged([string]$File, [string[]]$Arguments, [string]$Stem) {
    Write-NewJson ($Stem + '.command.json') ([ordered]@{
        executable = $File; arguments = $Arguments; workingDirectory = (Get-Location).Path
        startedUtc = [DateTime]::UtcNow.ToString('o')
    })
    $result = Invoke-NativeCapture $File $Arguments
    Write-NewText ($Stem + '.log') (($result.Lines -join [Environment]::NewLine) + [Environment]::NewLine)
    Write-NewJson ($Stem + '.exit.json') ([ordered]@{ exitCode = $result.ExitCode; finishedUtc = [DateTime]::UtcNow.ToString('o') })
    return $result
}

function Get-GitLines([string[]]$Arguments) {
    $result = Invoke-NativeCapture $script:GitExecutable $Arguments
    if ($result.ExitCode -ne 0) { throw "Git failed ($($result.ExitCode)): $($result.Lines -join ' ')" }
    return $result.Lines
}

function Get-InputSnapshot([string]$Repository) {
    $head = @(Get-GitLines @('rev-parse', '--verify', 'HEAD'))
    if ($head.Count -ne 1 -or $head[0] -notmatch '^[0-9a-f]{40}$') { throw 'Invalid HEAD.' }
    $dirty = @(Get-GitLines @('--no-optional-locks', 'status', '--porcelain=v1', '--untracked-files=no'))
    if ($dirty.Count -ne 0) { throw "Tracked inputs must be clean: $($dirty -join '; ')" }
    $tracked = @(Get-GitLines @('-c', 'core.quotepath=false', 'ls-files'))
    $paths = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    foreach ($relative in $tracked) {
        $path = [IO.Path]::GetFullPath((Join-FilePath $Repository $relative))
        if (-not (Test-Within $path $Repository)) { throw "Tracked path escapes repository: $relative" }
        [void]$paths.Add($path)
    }
    # Include untracked/ignored source, resources, Maven config and compiled
    # runtime inputs too. Reports, coverage and Surefire scratch files can change.
    foreach ($relative in @('src', '.mvn', 'target/classes', 'target/test-classes')) {
        foreach ($path in @(Get-SafeFiles (Join-FilePath $Repository $relative))) { [void]$paths.Add($path) }
    }
    $records = @(
        foreach ($path in @($paths | Sort-Object)) {
            Assert-NoReparse $path
            $item = Get-Item -LiteralPath $path -Force
            if ($item.PSIsContainer) { throw "Input is not a regular file: $path" }
            [ordered]@{
                path = $path.Substring($Repository.TrimEnd('\', '/').Length + 1).Replace('\', '/')
                bytes = $item.Length; sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
            }
        }
    )
    $environment = [ordered]@{}
    foreach ($name in @('JAVA_HOME','PATH','MAVEN_ARGS','MAVEN_OPTS','MAVEN_USER_HOME','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','MVNW_REPOURL','MVNW_VERBOSE')) {
        $environment[$name] = Get-TextHash ([string][Environment]::GetEnvironmentVariable($name))
    }
    $content = [ordered]@{ head = $head[0]; files = $records; environmentValueHashes = $environment }
    return [pscustomobject]@{ Head = $head[0]; Sha256 = Get-TextHash ($content | ConvertTo-Json -Depth 8 -Compress); Content = $content }
}

function Assert-SameSnapshot($Expected, $Actual) {
    if ($Expected.Head -cne $Actual.Head -or $Expected.Sha256 -cne $Actual.Sha256) {
        throw 'HEAD or source/build input fingerprint changed; the run is invalid.'
    }
}

function Assert-CompiledInputs([string]$Repository, [string[]]$Selectors) {
    $tracked = @(Get-GitLines @('-c', 'core.quotepath=false', 'ls-files'))
    $productClasses = @(Get-SafeFiles (Join-FilePath $Repository 'target/classes') | Where-Object { $_.EndsWith('.class') })
    if ($productClasses.Count -eq 0) { throw 'Compiled product classes are absent.' }
    foreach ($selector in $Selectors) {
        $parts = $selector.Split('#')
        $relative = $parts[0].Replace('.', '/')
        if ($tracked -cnotcontains ('src/test/java/' + $relative + '.java')) { throw "Selected source must be tracked and committed: $selector" }
        $source = Join-FilePath $Repository ('src/test/java/' + $relative + '.java')
        $compiled = Join-FilePath $Repository ('target/test-classes/' + $relative + '.class')
        Assert-NoReparse $source
        Assert-NoReparse $compiled
        if (-not (Test-Path -LiteralPath $compiled -PathType Leaf)) { throw "Compiled selected test is absent: $compiled" }
        if ((Get-Item -LiteralPath $compiled).Length -lt 8) { throw "Compiled selected test is incomplete: $compiled" }
        $sourceText = [IO.File]::ReadAllText($source)
        $pattern = '@RepeatedTest\(20\)\s+(?:public\s+)?void\s+' + [regex]::Escape($parts[1]) + '\s*\('
        if ([regex]::Matches($sourceText, $pattern).Count -ne 1) { throw "Expected one @RepeatedTest(20) method: $selector" }
        if ((Get-Item -LiteralPath $compiled).LastWriteTimeUtc -lt (Get-Item -LiteralPath $source).LastWriteTimeUtc) {
            throw "Selected compiled test is older than its source: $selector"
        }
    }
}

function Assert-BatchReports([string]$Directory, [string]$Suffix, [string[]]$Selectors, [DateTime]$StartedUtc) {
    $files = @(Get-SafeFiles $Directory | Where-Object { $_.EndsWith('.xml', [StringComparison]::OrdinalIgnoreCase) })
    $types = @($Selectors | ForEach-Object { $_.Split('#')[0] } | Sort-Object -Unique)
    if ($files.Count -ne $types.Count) { throw "Expected $($types.Count) fresh XML reports; found $($files.Count)." }
    $counts = New-Object 'System.Collections.Generic.Dictionary[string,int]' ([StringComparer]::Ordinal)
    foreach ($selector in $Selectors) { $counts.Add($selector, 0) }
    $cases = New-Object 'System.Collections.Generic.List[object]'
    foreach ($type in $types) {
        $path = Join-FilePath $Directory ("TEST-$type-$Suffix.xml")
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing fresh report: $path" }
        if ((Get-Item -LiteralPath $path).LastWriteTimeUtc -lt $StartedUtc) { throw "Stale report: $path" }
        $settings = New-Object Xml.XmlReaderSettings
        $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
        $settings.XmlResolver = $null
        $stream = [IO.File]::OpenRead($path)
        $reader = $null
        try {
            $reader = [Xml.XmlReader]::Create($stream, $settings)
            $xml = New-Object Xml.XmlDocument
            $xml.XmlResolver = $null
            $xml.Load($reader)
        } finally {
            if ($null -ne $reader) { $reader.Dispose() }
            $stream.Dispose()
        }
        $suite = $xml.DocumentElement
        if ($suite.LocalName -cne 'testsuite' -or $suite.NamespaceURI -cne '' -or $suite.GetAttribute('name') -cne "$type($Suffix)") { throw "Wrong suite identity: $path" }
        foreach ($attribute in @('skipped','errors','failures')) {
            if ($suite.GetAttribute($attribute) -cne '0') { throw "Nonzero/missing $attribute in $path" }
        }
        if ($suite.SelectNodes('.//failure|.//error|.//skipped|.//flakyFailure|.//flakyError|.//rerunFailure|.//rerunError').Count -ne 0) {
            throw "Failed, skipped, retried or flaky execution in $path"
        }
        $suiteCases = @($suite.SelectNodes('testcase'))
        if ($suite.GetAttribute('tests') -cne [string]$suiteCases.Count) { throw "Suite total disagrees with testcase elements: $path" }
        foreach ($case in $suiteCases) {
            $name = $case.GetAttribute('name')
            $className = $case.GetAttribute('classname')
            $key = $type + '#' + $name
            if ($className -cne "$type($Suffix)" -or -not $counts.ContainsKey($key)) { throw "Unexpected testcase: $className#$name" }
            $counts[$key]++
            $cases.Add([ordered]@{ selector = $key; classname = $className; name = $name; occurrence = $counts[$key]; report = [IO.Path]::GetFileName($path) })
        }
    }
    foreach ($selector in $Selectors) {
        if ($counts[$selector] -ne 20) { throw "Expected 20 executions of $selector; found $($counts[$selector])." }
    }
    if ($cases.Count -ne 160) { throw "Expected 160 executions in batch; found $($cases.Count)." }
    return [pscustomobject]@{ Counts = $counts; Cases = $cases.ToArray(); Total = $cases.Count }
}

function Copy-BatchReports([string]$Source, [string]$Destination, [string]$Suffix) {
    [void][IO.Directory]::CreateDirectory($Destination)
    Assert-NoReparse $Source
    if (-not (Test-Path -LiteralPath $Source -PathType Container)) { return }
    foreach ($file in Get-ChildItem -LiteralPath $Source -File -Force) {
        # Preserve every report bearing this fresh token, including unexpected
        # suites or text output. Existing reports belonging to callers stay put.
        if ($file.Name.Contains($Suffix)) {
            Assert-NoReparse $file.FullName
            [IO.File]::Copy($file.FullName, (Join-FilePath $Destination $file.Name), $false)
        }
    }
}

function Write-EvidenceHashes([string]$Directory) {
    $records = @(
        foreach ($path in @(Get-SafeFiles $Directory | Sort-Object)) {
            [ordered]@{ path = $path.Substring($Directory.Length + 1).Replace('\', '/'); sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() }
        }
    )
    Write-NewJson (Join-FilePath $Directory 'evidence-sha256.json') $records
}

function Invoke-RaceGate([string]$RunnerDirectory) {
    $repository = [IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($RunnerDirectory).TrimEnd('\', '/'))
    Assert-NoReparse $repository
    if (-not (Test-Path -LiteralPath (Join-FilePath $repository 'pom.xml') -PathType Leaf)) {
        throw 'Install this runner in the repository scripts directory before execution.'
    }
    $script:GitExecutable = (Get-Command git.exe -CommandType Application -ErrorAction Stop).Source
    $run = $null
    $failure = $null
    $acceptance = $null
    Push-Location -LiteralPath $repository
    try {
        $top = @(Get-GitLines @('rev-parse', '--show-toplevel'))
        if ($top.Count -ne 1 -or -not [IO.Path]::GetFullPath($top[0]).TrimEnd('\', '/').Equals($repository, [StringComparison]::OrdinalIgnoreCase)) { throw 'Wrong repository root.' }
        $tracked = @(Get-GitLines @('-c', 'core.quotepath=false', 'ls-files'))
        $protected = @('.git','src','target','.mvn','scripts','pom.xml','mvnw','mvnw.cmd') + $tracked
        $protected = @($protected | ForEach-Object { Join-FilePath $repository $_ })
        Assert-EvidenceLocation $EvidenceDirectory $protected
        # Deep evidence paths otherwise hit the Win32 MAX_PATH limit in PS 5.1.
        $base = Get-ExtendedPath $EvidenceDirectory
        [void][IO.Directory]::CreateDirectory($base)
        $token = [Guid]::NewGuid().ToString('N')
        $run = Join-FilePath $base ('race-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ') + '-' + $token)
        if (Test-Path -LiteralPath $run) { throw 'Run evidence already exists.' }
        [void][IO.Directory]::CreateDirectory($run)
        Write-NewText (Join-FilePath $run 'STATUS.started.txt') 'INCOMPLETE: a success.json file is required for acceptance.'
        Write-NewJson (Join-FilePath $run 'repository.json') ([ordered]@{ repository = $repository; head = @(Get-GitLines @('rev-parse','--verify','HEAD')); startedUtc = [DateTime]::UtcNow.ToString('o') })
        Write-NewJson (Join-FilePath $run 'selectors.json') $RaceSelectors
        $wrapper = Join-FilePath $repository 'mvnw.cmd'
        if ($env:MAVEN_ARGS) { throw 'MAVEN_ARGS must be empty; the runner owns the Maven goal and argv.' }
        $mavenConfig = Join-FilePath $repository '.mvn/maven.config'
        if ((Test-Path -LiteralPath $mavenConfig) -and -not [string]::IsNullOrWhiteSpace([IO.File]::ReadAllText($mavenConfig))) {
            throw '.mvn/maven.config must be absent or empty; no extra Maven goals/options are allowed.'
        }
        $initial = Get-InputSnapshot $repository
        Write-NewJson (Join-FilePath $run 'inputs-before.json') $initial
        Assert-CompiledInputs $repository $RaceSelectors
        [xml]$pom = [IO.File]::ReadAllText((Join-FilePath $repository 'pom.xml'))
        $ns = New-Object Xml.XmlNamespaceManager($pom.NameTable)
        $ns.AddNamespace('m', 'http://maven.apache.org/POM/4.0.0')
        $surefire = $pom.SelectNodes('/m:project/m:build/m:plugins/m:plugin[m:artifactId="maven-surefire-plugin"]', $ns)
        if ($surefire.Count -ne 1 -or $surefire[0].SelectSingleNode('m:version', $ns).InnerText -cne '3.2.5') { throw 'Expected Surefire 3.2.5.' }
        $java = if ($env:JAVA_HOME) { Join-FilePath $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java.exe -CommandType Application -ErrorAction Stop).Source }
        $javaVersion = Invoke-NativeLogged $java @('-version') (Join-FilePath $run 'java-version')
        if ($javaVersion.ExitCode -ne 0) { throw 'Java version command failed.' }
        $mavenVersion = Invoke-NativeLogged $wrapper @('-v') (Join-FilePath $run 'maven-version')
        if ($mavenVersion.ExitCode -ne 0) { throw 'Maven version command failed.' }
        $totals = New-Object 'System.Collections.Generic.Dictionary[string,int]' ([StringComparer]::Ordinal)
        foreach ($selector in $RaceSelectors) { $totals.Add($selector, 0) }
        $selectorArgument = '-Dtest=' + ($RaceSelectors -join ',')
        $reports = Join-FilePath $repository 'target/surefire-reports'
        for ($batch = 1; $batch -le 25; $batch++) {
            $batchDirectory = Join-FilePath $run ('batch-{0:D2}' -f $batch)
            [void][IO.Directory]::CreateDirectory($batchDirectory)
            $before = Get-InputSnapshot $repository
            Write-NewJson (Join-FilePath $batchDirectory 'inputs-before.json') $before
            Assert-SameSnapshot $initial $before
            $suffix = 'g1-' + $token + ('-{0:D2}' -f $batch)
            Assert-NoReparse $reports
            if (Test-Path -LiteralPath $reports -PathType Container) {
                if (@(Get-ChildItem -LiteralPath $reports -Force | Where-Object { $_.Name.Contains($suffix) }).Count -ne 0) { throw 'Fresh report suffix is already present.' }
            }
            $arguments = @('-B','-ntp','surefire:test', $selectorArgument,
                ('-Dsurefire.reportNameSuffix=' + $suffix), '-Dsurefire.rerunFailingTestsCount=0',
                '-DskipTests=false','-Dmaven.test.skip=false','-Dmaven.test.failure.ignore=false',
                '-DdisableXmlReport=false','-DfailIfNoTests=true','-Dsurefire.failIfNoSpecifiedTests=true')
            $started = [DateTime]::UtcNow
            $savedReports = Join-FilePath $batchDirectory 'reports'
            try {
                $result = Invoke-NativeLogged $wrapper $arguments (Join-FilePath $batchDirectory 'maven')
            } finally {
                # Copy partial reports even when launch/capture failed; no next
                # invocation is possible until this finally block completes.
                Copy-BatchReports $reports $savedReports $suffix
            }
            $after = Get-InputSnapshot $repository
            Write-NewJson (Join-FilePath $batchDirectory 'inputs-after.json') $after
            Assert-SameSnapshot $initial $after
            if ($result.ExitCode -ne 0) { throw "Batch $batch exited $($result.ExitCode); no retries or later batches." }
            $validated = Assert-BatchReports $savedReports $suffix $RaceSelectors $started
            Write-NewJson (Join-FilePath $batchDirectory 'cases.json') $validated.Cases
            foreach ($selector in $RaceSelectors) { $totals[$selector] += $validated.Counts[$selector] }
            Write-NewJson (Join-FilePath $batchDirectory 'accepted.json') ([ordered]@{ batch = $batch; executions = $validated.Total; suffix = $suffix })
        }
        $final = Get-InputSnapshot $repository
        Write-NewJson (Join-FilePath $run 'inputs-after.json') $final
        Assert-SameSnapshot $initial $final
        $arithmetic = @(
            foreach ($selector in $RaceSelectors) {
                if ($totals[$selector] -ne 500) { throw "Final total is not 500: $selector" }
                [ordered]@{ selector = $selector; batches = 25; perBatch = 20; actual = $totals[$selector] }
            }
        )
        $grandTotal = ($totals.Values | Measure-Object -Sum).Sum
        if ($grandTotal -ne 4000) { throw 'Final total is not 4000.' }
        $acceptance = [ordered]@{ head = $initial.Head; inputSha256 = $initial.Sha256; batches = 25; executions = $grandTotal; methods = $arithmetic; failures = 0; errors = 0; skipped = 0 }
        Write-NewJson (Join-FilePath $run 'result.json') $acceptance
    } catch {
        $failure = $_
        if ($null -ne $run) {
            Write-NewJson (Join-FilePath $run 'failure.json') ([ordered]@{ message = $_.Exception.Message; detail = $_.ToString(); utc = [DateTime]::UtcNow.ToString('o') })
            # Best-effort diagnostic only. Failure never turns into acceptance.
            try { Write-NewJson (Join-FilePath $run 'inputs-on-failure.json') (Get-InputSnapshot $repository) }
            catch { Write-NewText (Join-FilePath $run 'inputs-on-failure-error.txt') $_.ToString() }
        }
    } finally {
        try { if ($null -ne $run) { Write-EvidenceHashes $run } }
        finally { Pop-Location }
    }
    if ($null -ne $failure) { throw $failure }
    # The acceptance marker is last: a hashing failure must not leave a success.
    $acceptance['evidenceManifestSha256'] = (Get-FileHash -LiteralPath (Join-FilePath $run 'evidence-sha256.json') -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-NewJson (Join-FilePath $run 'success.json') $acceptance
    Write-Output "Accepted 25 batches / 500 per method / 4000 executions. Evidence: $run"
}

Invoke-RaceGate $PSScriptRoot
