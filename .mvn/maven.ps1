$MavenArguments = @($args)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $taskRoot
$taskMavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if ($taskMavenCommand) {
    $taskMaven = $taskMavenCommand.Source
} elseif ($env:MAVEN_HOME -and (Test-Path -LiteralPath (Join-Path $env:MAVEN_HOME 'bin\mvn.cmd'))) {
    $taskMaven = Join-Path $env:MAVEN_HOME 'bin\mvn.cmd'
} else {
    $taskVersion = '3.9.11'
    $taskTools = Join-Path $taskRoot '.tools'
    $taskMaven = Join-Path $taskTools "apache-maven-$taskVersion\bin\mvn.cmd"
    if (-not (Test-Path -LiteralPath $taskMaven)) {
        New-Item -ItemType Directory -Path $taskTools -Force | Out-Null
        $taskUrl = "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$taskVersion/apache-maven-$taskVersion-bin.zip"
        $taskArchive = Join-Path $taskTools 'maven.zip'
        try {
            Write-Host '首次构建：下载 Maven，并校验 SHA-512。'
            [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
            Invoke-WebRequest -Uri $taskUrl -OutFile $taskArchive -UseBasicParsing -TimeoutSec 120
            $taskExpected = ((Invoke-WebRequest -Uri ($taskUrl + '.sha512') -UseBasicParsing -TimeoutSec 30).Content.Trim() -split '\s+')[0]
            $taskActual = (Get-FileHash -LiteralPath $taskArchive -Algorithm SHA512).Hash
            if ($taskActual -ine $taskExpected) { throw 'Maven 文件校验失败' }
            Expand-Archive -LiteralPath $taskArchive -DestinationPath $taskTools -Force
        } catch {
            Write-Error ('无法准备 Maven：' + $_.Exception.Message + '。可安装 Maven 或设置 MAVEN_HOME 后重试。')
            exit 1
        }
    }
}
& $taskMaven '-Dmaven.repo.local=.tools/repository' @MavenArguments
exit $LASTEXITCODE
