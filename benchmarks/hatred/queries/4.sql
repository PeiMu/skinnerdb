SELECT hatred_1.keyword AS keyword,   hatred_1.state_ AS state_,   SUM(1) AS tempcalculation_32770727399760281621097698410,   CAST(MAX(hatred_1.statepopnum) AS LONG) AS tempcalculation_32770727399760281634510887090,   CAST(MIN(hatred_1.statepopnum) AS LONG) AS tempcalculation_32770727399760281638559701140 FROM hatred_1 WHERE ((hatred_1.keyword IN ('camel jockey', 'dyke', 'japs', 'jungle bunnies', 'kike', 'lesbo', 'man in a dress', 'men in dresses', 'midget', 'mongoloid', 'porch monkey', 'twat', 'wetback', 'whitey', 'yid')) OR ((hatred_1.keyword >= 'beaner') AND (hatred_1.keyword <= 'bimbo')) OR ((hatred_1.keyword >= 'chauvinist pig') AND (hatred_1.keyword <= 'cripple')) OR ((hatred_1.keyword >= 'dindu') AND (hatred_1.keyword <= 'dothead')) OR ((hatred_1.keyword >= 'fatass') AND (hatred_1.keyword <= 'injun')) OR ((hatred_1.keyword >= 'nigger') AND (hatred_1.keyword <= 'papist')) OR ((hatred_1.keyword >= 'rag head') AND (hatred_1.keyword <= 'sandnigger')) OR ((hatred_1.keyword >= 'shemale') AND (hatred_1.keyword <= 'spic')) OR ((hatred_1.keyword >= 'towel head') AND (hatred_1.keyword <= 'tranny'))) GROUP BY hatred_1.keyword,   hatred_1.state_ HAVING ((SUM(1) >= 30) AND (SUM(1) <= 100000));