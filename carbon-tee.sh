#!/bin/bash
# NOTE: this script is pretty flaky.  Please use the built-in tee capability in CarbonInflux.

if [ $# != 5 ]
then
    echo "usage: $0 <src-port> <dest1-host> <dest1-port> <dest2-host> <dest2-port>"
    exit 0
fi

TMP=`mktemp -d`
SOURCE=$TMP/pipe.source
trap 'rm -rf "$TMP"' EXIT
mkfifo -m 0600 "$SOURCE"
echo Made fifo $SOURCE
nc "$4" "$5" <"$SOURCE" &
echo Started forking from $SOURCE to $4 $5
# -k is needed for Carbon/Graphite because connection may be closed after each batch
nc -k -l "$1" | tee "$SOURCE" | nc "$2" "$3"
