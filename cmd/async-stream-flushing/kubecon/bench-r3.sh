#/bin/sh

SCRIPT_DIR=$(dirname "$0")

VERSION=$(nats --user sys --password sys server request variables 1 | jq -r .server.ver)
# Default
URL="nats://localhost:4222"

reset() {
    nats str rm r3 --force > /dev/null 2>&1
    $SCRIPT_DIR/setup-r3.sh > /dev/null 2>&1
}

seturl() {
    LEADER=$(nats stream info r3 --json | jq -r .cluster.leader)
    PORT=$(nats --user sys --password sys server request variables --name $LEADER | jq -r .data.port)
    URL="nats://localhost:$PORT"
}


for size in 128 256 512 1024 2048 4096 8192 16384; do
    echo "Size: $size"

    reset

    echo "$VERSION: Batch 1"
    seturl
    nats -s $URL bench js pub sync --stream r3 --multisubject --size $size r3 --no-progress --msgs 500_000

    reset

    echo "$VERSION: Batch 100"
    seturl
    nats -s $URL bench js pub async --stream r3 --multisubject --size $size --batch 100 r3 --no-progress --msgs 500_000

    reset

    echo "$VERSION: Batch 500"
    seturl
    nats -s $URL bench js pub async --stream r3 --multisubject --size $size --batch 500 r3 --no-progress --msgs 500_000

    reset
done
