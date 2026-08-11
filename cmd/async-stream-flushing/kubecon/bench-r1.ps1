$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

$reset = {
    nats str rm sync --force 2>$null
    nats str rm async --force 2>$null
    & "$scriptDir\setup-r1.ps1" 2>$null
}

$sizes = @(128, 256, 512, 1024, 2048, 4096, 8192, 16384)

foreach ($size in $sizes) {
    Write-Host "Size: $size"

    & $reset

    Write-Host "Sync Flush: Batch 1"
    nats bench js pub sync --stream sync --multisubject --size $size sync --no-progress --msgs 500_000

    & $reset

    Write-Host "Sync Flush: Batch 100"
    nats bench js pub async --stream sync --multisubject --size $size --batch 100 sync --no-progress --msgs 500_000

    & $reset

    Write-Host "Sync Flush: Batch 500"
    nats bench js pub async --stream sync --multisubject --size $size --batch 500 sync --no-progress --msgs 500_000

    & $reset

    Write-Host "Async Flush: Batch 1"
    nats bench js pub sync --stream async --multisubject --size $size async --no-progress --msgs 500_000

    & $reset

    Write-Host "Async Flush: Batch 100"
    nats bench js pub async --stream async --multisubject --size $size --batch 100 async --no-progress --msgs 500_000

    & $reset

    Write-Host "Async Flush: Batch 500"
    nats bench js pub async --stream async --multisubject --size $size --batch 500 async --no-progress --msgs 500_000

    & $reset
}
