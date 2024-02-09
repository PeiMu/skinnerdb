DBDIR=$1
NETHREADS=$2
java -jar -Xmx200g -Djava.util.concurrent.ForkJoinPool.common.parallelism=$2 Skinner.jar $1 $2
