#!/usr/bin/env bash

if (( $# == 1 )); then
    FILE=$1
else
    FILE=output.out
    echo "using default file, '$0 (file)' to change it"
fi

echo "-- $FILE --"
grep --color=auto -i -n -G "\(RUN .* BENCHMARK\)\|\(OutOfMemoryError\|Exception\)\|\(generating output\)\|\(Benchmark done\)" $FILE
echo "------------------"
ps ux | grep --color=auto -i -G "\(COMMAND\)\|\(./benchmark.sh\)\|\(endpoint.jar\)"