param(
    [ValidateSet('all', 'ollama', 'backend', 'frontend', 'router')][string]$Service = 'all',
    [switch]$Build,
    [switch]$PullModel
)
. "$PSScriptRoot/local-common.ps1"
$needed = if ($Service -eq 'all') { @('ollama', 'backend', 'router', 'frontend') } else { @($Service) }
foreach ($item in $needed) {
    switch ($item) {
        'ollama' {
            if (-not (Test-LocalHttp 'http://127.0.0.1:11434/api/version')) {
                Assert-FreePort 11434
                Start-LocalProcess 'ollama' $OllamaExe @('serve') (Split-Path $OllamaExe)
                Wait-LocalHttp 'ollama' 'http://127.0.0.1:11434/api/version'
            }
            $tags = Invoke-RestMethod -Uri 'http://127.0.0.1:11434/api/tags' -TimeoutSec 5
            if ($LocalConfig.model -notin @($tags.models.name)) {
                if (-not $PullModel) { throw "Model $($LocalConfig.model) is missing. Run start-local.ps1 -Service ollama -PullModel." }
                & $OllamaExe pull $LocalConfig.model
                if ($LASTEXITCODE -ne 0) { throw 'Model download failed.' }
            }
        }
        'backend' {
            if (Get-ServiceRecord 'backend') {
                if ($Build) { throw 'Stop the managed backend before rebuilding it.' }
                Write-Host 'backend already running.'
                continue
            }
            Assert-FreePort 5000
            $jar = Join-Path $ProjectRoot 'backend/target/local-ai-agent-1.0.0.jar'
            if ($Build -or -not (Test-Path -LiteralPath $jar)) {
                & $MavenExe "-Dmaven.repo.local=$MavenRepository" '-f' (Join-Path $ProjectRoot 'backend/pom.xml') 'verify'
                if ($LASTEXITCODE -ne 0) { throw 'Backend build or tests failed.' }
            }
            Start-LocalProcess 'backend' $JavaExe @('-Xms128m', '-Xmx384m', '-Dfile.encoding=UTF-8', '-jar', ('"' + $jar + '"')) $ProjectRoot
            Wait-LocalHttp 'backend' 'http://127.0.0.1:5000/api/health'
        }
        'router' {
            if (Get-ServiceRecord 'router') { Write-Host 'router already running.'; continue }
            Assert-FreePort 5002
            $dir = Join-Path $ProjectRoot 'router-layer'
            if (-not (Test-Path -LiteralPath (Join-Path $dir 'node_modules'))) {
                & $NpmExe --prefix $dir ci
                if ($LASTEXITCODE -ne 0) { throw 'Router dependencies failed.' }
            }
            Start-LocalProcess 'router' $NodeExe @(('"' + (Join-Path $dir 'server.js') + '"')) $dir
            Wait-LocalHttp 'router' 'http://127.0.0.1:5002/health'
        }
        'frontend' {
            if (Get-ServiceRecord 'frontend') { Write-Host 'frontend already running.'; continue }
            Assert-FreePort 3000
            $dir = Join-Path $ProjectRoot 'frontend'
            if (-not (Test-Path -LiteralPath (Join-Path $dir 'node_modules'))) {
                & $LocalConfig.pnpmExe --dir $dir install --frozen-lockfile --store-dir (Join-Path $LocalConfig.cacheDir 'pnpm')
                if ($LASTEXITCODE -ne 0) { throw 'Frontend dependencies failed.' }
            }
            Start-LocalProcess 'frontend' $NodeExe @(('"' + (Join-Path $dir 'node_modules/vite/bin/vite.js') + '"'), '--host', '127.0.0.1', '--port', '3000', '--strictPort') $dir
            Wait-LocalHttp 'frontend' 'http://127.0.0.1:3000'
        }
    }
}
Write-Host 'Frontend http://127.0.0.1:3000 | Backend http://127.0.0.1:5000 | Router http://127.0.0.1:5002'
