# TongCut Codex wave 1 launcher for Windows PowerShell 5.1+ / PowerShell 7
# Run from a local clone of the private TongCut repo AFTER installing/signing in to Codex CLI.
# Default max concurrency: four, to prevent 20 simultaneous builds exhausting RAM/credits.
# Example: .\tools\start-codex-wave1.ps1 -MaxParallel 4
# Optional: .\tools\start-codex-wave1.ps1 -AgentIds A20,A10,A03,A06 -MaxParallel 2
param(
    [ValidateRange(1,20)][int]$MaxParallel = 4,
    [string[]]$AgentIds = @()
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not (Get-Command git -ErrorAction SilentlyContinue)) { throw 'Install Git first.' }
if (-not (Get-Command codex -ErrorAction SilentlyContinue)) { throw 'Install and sign in to Codex CLI first. Run codex interactively once to authenticate.' }
$repoCheck = (& git -C $repo rev-parse --is-inside-work-tree).Trim()
if ($LASTEXITCODE -ne 0 -or $repoCheck -ne 'true') { throw 'Run this script from inside a TongCut Git checkout.' }
$origin = (& git -C $repo remote get-url origin).Trim()
if ($origin -notmatch 'manbir6-ux[/\\]TongCut(?:\.git)?$') { throw "Unexpected origin: $origin" }
& git -C $repo fetch --prune origin
if ($LASTEXITCODE -ne 0) { throw 'Git fetch failed. Check your GitHub access.' }

$agents = @(
    @{ Id = 'A20'; Issue = 26; Slug = 'integration' },
    @{ Id = 'A10'; Issue = 16; Slug = 'qa-regression' },
    @{ Id = 'A03'; Issue = 9; Slug = 'project-store' },
    @{ Id = 'A06'; Issue = 12; Slug = 'audio-studio' },
    @{ Id = 'A07'; Issue = 13; Slug = 'colour-luts' },
    @{ Id = 'A08'; Issue = 14; Slug = 'captions' },
    @{ Id = 'A01'; Issue = 7; Slug = 'home-ui' },
    @{ Id = 'A02'; Issue = 8; Slug = 'timeline' },
    @{ Id = 'A04'; Issue = 10; Slug = 'export-core' },
    @{ Id = 'A05'; Issue = 11; Slug = 'motion-core' },
    @{ Id = 'A09'; Issue = 15; Slug = 'face-reshape' },
    @{ Id = 'A11'; Issue = 17; Slug = 'photo-overlays' },
    @{ Id = 'A12'; Issue = 18; Slug = 'video-pip' },
    @{ Id = 'A13'; Issue = 19; Slug = 'reverse' },
    @{ Id = 'A14'; Issue = 20; Slug = 'tracking' },
    @{ Id = 'A15'; Issue = 21; Slug = 'effects-packs' },
    @{ Id = 'A16'; Issue = 22; Slug = 'export-profiles' },
    @{ Id = 'A17'; Issue = 23; Slug = 'accessibility' },
    @{ Id = 'A18'; Issue = 24; Slug = 'performance' },
    @{ Id = 'A19'; Issue = 25; Slug = 'security' }
)
if ($AgentIds.Count -gt 0) {
    $unknown = @($AgentIds | Where-Object { $_ -notin @($agents | ForEach-Object { $_.Id }) })
    if ($unknown.Count -gt 0) { throw "Unknown agent ID(s): $($unknown -join ', ')" }
    $agents = @($agents | Where-Object { $_.Id -in $AgentIds })
}
$parent = Split-Path -Parent $repo
$logDir = Join-Path $parent 'TongCut-codex-logs'
New-Item -ItemType Directory -Path $logDir -Force | Out-Null
$jobs = @()

foreach ($agent in $agents) {
    while (@($jobs | Where-Object { $_.State -eq 'Running' }).Count -ge $MaxParallel) {
        Start-Sleep -Seconds 5
    }
    $id = $agent.Id
    $branch = "agent/$($agent.Id)-$($agent.Slug)"
    $remoteBranch = "origin/$branch"
    $wt = Join-Path $parent "TongCut-$id"

    if (-not (Test-Path $wt)) {
        # Remote branches have already been reserved by the project coordinator.
        $localExisting = & git -C $repo branch --list $branch
        if ($localExisting) {
            & git -C $repo worktree add $wt $branch
        } else {
            & git -C $repo worktree add --track -b $branch $wt $remoteBranch
        }
        if ($LASTEXITCODE -ne 0) { throw "Cannot create isolated worktree for $id" }
    }
    $activeBranch = (& git -C $wt branch --show-current).Trim()
    if ($activeBranch -ne $branch) { throw "Existing worktree $wt is on $activeBranch, expected $branch." }

    $issueUrl = "https://github.com/manbir6-ux/TongCut/issues/$($agent.Issue)"
    $prompt = @"
You are TongCut coding Agent $id. Work ONLY on branch $branch in your dedicated Git worktree.
Assignment: $issueUrl
Read AGENTS.md, docs/CODEX_AGENT_WAVE1.md, the assigned GitHub issue and docs/TEMP_MASTER_PROJECT_CHECKLIST.md.
Implement this exact issue using the existing Kotlin/Android/Media3 project, only edit files explicitly owned by your issue, and add module-specific tests.
Never modify common hot files without written A20 coordination; do not copy CapCut source/assets; do not claim untested behavior.
Perform appropriate local checks when environment permits. Commit your work to this branch; if remote GitHub access and approval permit, push and open a PR toward development/capcut-parity. Do not merge any PR or ship an APK.
If blocked on shared files, describe the exact integration patch needed rather than editing the file. Summarize completed code, failed/passed tests, PR URL if any, and unresolved constraints.
"@
    $logFile = Join-Path $logDir "$id.log"
    Write-Host "Starting $id ($branch) — issue #$($agent.Issue)"
    $jobs += Start-Job -Name $id -ArgumentList $wt, $prompt, $logFile -ScriptBlock {
        param($workingDir, $taskPrompt, $logPath)
        & codex exec --sandbox workspace-write -C $workingDir $taskPrompt 2>&1 |
            Out-File -FilePath $logPath -Encoding utf8
        if ($LASTEXITCODE -ne 0) { throw "Codex exec failed with exit code $LASTEXITCODE; see $logPath" }
        "Completed: $workingDir"
    }
}
Write-Host "All $($agents.Count) assigned sessions dispatched in batches of up to $MaxParallel. Logs: $logDir"
$jobs | Wait-Job | Out-Null
foreach ($job in $jobs) {
    Write-Host "$($job.Name): $($job.State)"
    if ($job.State -ne 'Completed') { Receive-Job -Job $job -Keep -ErrorAction Continue }
}
Write-Host 'Review each task log and code/PR individually; no changes are automatically merged.'
