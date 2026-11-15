param([switch]$ResetData)
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
function Remove-ProjectArtifact([string]$RelativePath) {
    $taskTarget = [IO.Path]::GetFullPath((Join-Path $taskRoot $RelativePath))
    if (-not $taskTarget.StartsWith($taskRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw '清理目标不在项目目录中' }
    if (Test-Path -LiteralPath $taskTarget) {
        $taskItem = Get-Item -LiteralPath $taskTarget -Force
        if (($taskItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw '不能递归清理链接目录' }
        if ($taskItem.PSIsContainer -and @(Get-ChildItem -LiteralPath $taskTarget -Recurse -Force -Attributes ReparsePoint).Count -gt 0) { throw '清理目录中包含链接，已停止' }
        Remove-Item -LiteralPath $taskTarget -Recurse -Force
    }
}
foreach ($taskArtifact in @('target/test-data','target/test-postgres','target/test-runtime','target/test-runner','target/test-classes','target/generated-test-sources','target/surefire-reports','target/offline-checks','target/ui-check','tools/__pycache__','tests/__pycache__','legacy/__pycache__','__pycache__','shots')) {
    Remove-ProjectArtifact $taskArtifact
}
if ($ResetData) { Remove-ProjectArtifact 'data' }
Get-ChildItem -LiteralPath $taskRoot -File -Filter '.project-info-*.md' -Force | ForEach-Object { Remove-ProjectArtifact $_.Name }
Write-Host '测试数据、临时文件与测试报告已清理。'
if ($ResetData) { Write-Host '正式数据库及其备份已清空。' }
