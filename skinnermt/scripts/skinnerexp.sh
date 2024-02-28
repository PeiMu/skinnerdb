#!/bin/bash
RUNS=$1
BENCH=$2
ALGO=$3
DIR=imdb
RES=imdb
QUERIES=imdb
# Replace these two variables for your customized experiments
MEM=200
SKINNER_DIR=/home/pei/Project/skinnerdb/skinnermt
GCOPTION=G1
if [ $BENCH == "imdb_s" ]
then
    DIR=imdb_s
    RES=imdb_s
elif [ $BENCH == "imdb" ]
then
    DIR=imdb
    RES=imdb
    QUERIES=/home/pei/benchmarks/imdb_job-postgres/skinnerdb_queries
elif [ $BENCH == "tpch-10" ]
then
    DIR=tpch-sf-10
    RES=tpch-sf-10
elif [ $BENCH == "tpch-3" ]
then
    DIR=tpch-sf-3
    RES=tpch-sf-3
    QUERIES=/home/pei/benchmarks/tpch-postgre/dbgen/out/skinner_pure_queries
elif [ $BENCH == "test" ]
then
    DIR=test
    RES=test
else
    DIR=jcch-sf-10
    RES=jcchx
fi
PREFIX=$SKINNER_DIR/$DIR
if [ $ALGO == "dp" ]
then
    sed -i "1s/.*/PARALLEL_ALGO=DP/g" $PREFIX/config.sdb
elif [ $ALGO == "sp" ]
then
    sed -i "1s/.*/PARALLEL_ALGO=SP-U/g" $PREFIX/config.sdb
elif [ $ALGO == "spa" ]
then 
    sed -i "1s/.*/PARALLEL_ALGO=SP-A/g" $PREFIX/config.sdb
elif [ $ALGO == "hpa" ]
then
    sed -i "1s/.*/PARALLEL_ALGO=HP-A/g" $PREFIX/config.sdb
elif [ $ALGO == "dpl"  ]
then
    sed -i "1s/.*/PARALLEL_ALGO=DPL/g" $PREFIX/config.sdb
elif [ $ALGO == "dpm"  ]
then
    sed -i "1s/.*/PARALLEL_ALGO=DPM/g" $PREFIX/config.sdb
else
    sed -i "1s/.*/PARALLEL_ALGO=HP/g" $PREFIX/config.sdb
fi

if [ $GCOPTION == "epsilon" ]
then
    GCCOMMAND="-XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC -XX:+AlwaysPreTouch"
else
    GCCOMMAND=""
fi

for t in 1 4 8 12 16 20 24
do    
    for i in `seq 1 $RUNS`
    do
        echo "Benchmarking $BENCH optimal #$i"
        COMMAND="bench ${QUERIES} ./${RES}-${ALGO}-${t}_${i}.txt"
        echo $COMMAND
        echo -ne "$COMMAND\nquit\n" | java -jar ${GCCOMMAND} -Xmx${MEM}G -Xms${MEM}G Skinner.jar ${PREFIX} $t
    done
done
