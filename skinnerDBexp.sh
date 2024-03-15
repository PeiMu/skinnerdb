#!/bin/bash
RUNS=$1
BENCH=$2
DIR=imdb
RES=imdb
QUERIES=imdb
# Replace these two variables for your customized experiments
MEM=16
SKINNER_DIR=/home/pei/Project/skinnerdb
if [ $BENCH == "imdb_s" ]
then
    DIR=imdb_s
    RES=imdb_s
elif [ $BENCH == "imdb" ]
then
    DIR=imdbskinner/skinnerimdb
    RES=imdb
    QUERIES=/home/pei/Project/benchmarks/imdb_job-postgres/skinnerdb_queries/
elif [ $BENCH == "tpch-10" ]
then
    DIR=tpch-sf-10
    RES=tpch-sf-10
elif [ $BENCH == "tpch-1" ]
then
    DIR=tpch
    RES=tpch-1
    QUERIES=/home/pei/Project/benchmarks/tpch-postgres/dbgen/out/skinner_pure_queries/
elif [ $BENCH == "jcch-1" ]
then
    DIR=jcch
    RES=jcch-1
    QUERIES=/home/pei/Project/benchmarks/JCC-H/out/skinner_pure_queries/
elif [ $BENCH == "test" ]
then
    DIR=test
    RES=test
else
    DIR=jcch-sf-10
    RES=jcchx
fi
PREFIX=$SKINNER_DIR/$DIR
for i in `seq 1 $RUNS`
do
    echo "Benchmarking $BENCH optimal #$i"
    COMMAND="index all\nbench ${QUERIES} ./${RES}_${i}.txt"
    echo $COMMAND
    echo -ne "$COMMAND\nquit\n" | java -jar -Xmx${MEM}G -Xms${MEM}G -XX:+UseConcMarkSweepGC jars/Skinner.jar ${PREFIX}
done

