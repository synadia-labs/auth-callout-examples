#/bin/sh

SCRIPT_DIR=$(dirname "$0")

reset() {
    nats str rm sync --force > /dev/null 2>&1
    nats str rm async --force > /dev/null 2>&1
    $SCRIPT_DIR/setup-r1.sh > /dev/null 2>&1
}

for size in 128 256 512 1024 2048 4096 8192 16384; do
    echo "Size: $size"

    reset

    echo "Sync Flush: Batch 1"
    nats bench js pub sync --stream sync --multisubject --size $size sync --no-progress --msgs 500_000

    reset

    echo "Sync Flush: Batch 100"
    nats bench js pub async --stream sync --multisubject --size $size --batch 100 sync --no-progress --msgs 500_000

    reset

    echo "Sync Flush: Batch 500"
    nats bench js pub async --stream sync --multisubject --size $size --batch 500 sync --no-progress --msgs 500_000

    reset

    echo "Async Flush: Batch 1"
    nats bench js pub sync --stream async --multisubject --size $size async --no-progress --msgs 500_000

    reset

    echo "Async Flush: Batch 100"
    nats bench js pub async --stream async --multisubject --size $size --batch 100 async --no-progress --msgs 500_000

    reset

    echo "Async Flush: Batch 500"
    nats bench js pub async --stream async --multisubject --size $size --batch 500 async --no-progress --msgs 500_000

    reset
done
