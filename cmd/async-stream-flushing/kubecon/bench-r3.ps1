$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# Get NATS server version
$versionOutput = nats --user sys --password sys server request variables 1 2>$null | ConvertFrom-Json
$VERSION = $versionOutput.server.ver

# Default URL
$URL = "nats://localhost:4222"

$reset = {
    nats str rm r3 --force 2>$null
    & "$scriptDir\setup-r3.ps1" 2>$null
}

$seturl = {
    $streamInfo = nats stream info r3 --json 2>$null | ConvertFrom-Json
    $leader = $streamInfo.cluster.leader
    $serverVars = nats --user sys --password sys server request variables --name $leader 2>$null | ConvertFrom-Json
    $port = $serverVars.data.port
    $script:URL = "nats://localhost:$port"
}

$sizes = @(128, 256, 512, 1024, 2048, 4096, 8192, 16384)

foreach ($size in $sizes) {
    Write-Host "Size: $size"

    & $reset

    Write-Host "$VERSION : Batch 1"
    & $seturl
    nats -s $URL bench js pub sync --stream r3 --multisubject --size $size r3 --no-progress --msgs 500_000

    & $reset

    Write-Host "$VERSION : Batch 100"
    & $seturl
    nats -s $URL bench js pub async --stream r3 --multisubject --size $size --batch 100 r3 --no-progress --msgs 500_000

    & $reset

    Write-Host "$VERSION : Batch 500"
    & $seturl
    nats -s $URL bench js pub async --stream r3 --multisubject --size $size --batch 500 r3 --no-progress --msgs 500_000

    & $reset
}
