param([switch]$SkipCompile)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
if (-not $SkipCompile) {
    & .\mvnw.cmd -o -B test-compile
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
$taskRoot = (Get-Location).Path
New-Item -ItemType Directory -Path 'target/test-runner' -Force | Out-Null
New-Item -ItemType Directory -Path 'target/test-runner/lib' -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskArchive = [IO.Compression.ZipFile]::OpenRead((Join-Path $taskRoot 'target/cczx-RadioStation.jar'))
try {
    foreach ($taskEntry in $taskArchive.Entries) {
        if ($taskEntry.FullName.StartsWith('BOOT-INF/lib/') -and $taskEntry.FullName.EndsWith('.jar')) {
            $taskDestination = Join-Path $taskRoot ('target/test-runner/lib/' + [IO.Path]::GetFileName($taskEntry.FullName))
            [IO.Compression.ZipFileExtensions]::ExtractToFile($taskEntry, $taskDestination, $true)
        }
    }
} finally { $taskArchive.Dispose() }
$taskMainJars = Get-ChildItem -LiteralPath 'target/test-runner/lib' -Filter '*.jar' -File | ForEach-Object { $_.FullName.Substring($taskRoot.Length + 1).Replace('\','/') }
$taskTestNames = @('spring-boot-test','spring-boot-test-autoconfigure','spring-test','junit-jupiter-api','junit-jupiter-params','junit-jupiter-engine','junit-platform-commons','junit-platform-engine','opentest4j','apiguardian-api','mockito-core','mockito-junit-jupiter','byte-buddy','byte-buddy-agent','objenesis','hamcrest','assertj-core','json-path','json-smart','accessors-smart','asm')
$taskTestJars = Get-ChildItem -LiteralPath '.tools/repository' -Filter '*.jar' -File -Recurse | Where-Object { $taskTestNames -contains $_.Directory.Parent.Name } | Group-Object { $_.Directory.Parent.Name } | ForEach-Object { ($_.Group | Sort-Object { $_.Directory.Name } -Descending | Select-Object -First 1).FullName.Substring($taskRoot.Length + 1).Replace('\','/') }
$taskClasspath = 'target/classes;target/test-classes;target/test-runner;' + (($taskMainJars + $taskTestJars) -join ';')
$taskCompileArgs = '-encoding UTF-8 -cp "' + $taskClasspath + '" -d target/test-runner tools/LocalTestRunner.java'
[IO.File]::WriteAllText((Join-Path $taskRoot 'target/test-runner/compile.args'), $taskCompileArgs, [Text.UTF8Encoding]::new($false))
& javac '@target/test-runner/compile.args'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$taskRunArgs = '-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -cp "' + $taskClasspath + '" com.cczxcsc.testing.LocalTestRunner'
[IO.File]::WriteAllText((Join-Path $taskRoot 'target/test-runner/run.args'), $taskRunArgs, [Text.UTF8Encoding]::new($false))
& java '@target/test-runner/run.args'
exit $LASTEXITCODE
