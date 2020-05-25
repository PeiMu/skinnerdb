#!/bin/sh

if [ "$#" -ne 1 ]; then
    echo "Usage: table-conversion.sh [input file]"
    exit
fi

CONTENT=`cat $1`

# Get rid of spaces/parens/slash/- in quotes
CONTENT=`echo "$CONTENT" | perl -pe 's:"[^"]*":($x=$&)=~s/\(|\)|-|\///g;$x:ge'`
CONTENT=`echo "$CONTENT" | perl -pe 's:"[^"]*":($x=$&)=~s/ |\(|\)/_/g;$x:ge'`

# Get rid of non-ascii characters
CONTENT=`echo "$CONTENT" | perl -pe 's/[^[:ascii:]]+//g'`

# Get rid of quotes/question marks
CONTENT=`echo "$CONTENT" | perl -pe 's/"//g'`
CONTENT=`echo "$CONTENT" | perl -pe 's/\?//g'`

# Change types
CONTENT=`echo "$CONTENT" | perl -pe 's/bigint/long/g'`
CONTENT=`echo "$CONTENT" | perl -pe 's/smallint|boolean/int/g'`
CONTENT=`echo "$CONTENT" | perl -pe 's/decimal\(.*\)/double/g'`

# Convert to lowercase
CONTENT=`echo "$CONTENT" | perl -ne "print lc"`

echo "$CONTENT"
