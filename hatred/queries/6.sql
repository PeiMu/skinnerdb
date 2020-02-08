SELECT hatred_1.keyword AS keyword,   hatred_1.state_ AS state_,   SUM(CAST(hatred_1.number_of_records AS LONG)) AS sumnumber_of_recordsok FROM hatred_1 WHERE ((hatred_1.keyword IN ('camel jockey', 'lesbo', 'man in a dress', 'men in dresses', 'porch monkey')) OR ((hatred_1.keyword >= 'beaner') AND (hatred_1.keyword <= 'bimbo')) OR ((hatred_1.keyword >= 'chauvinist pig') AND (hatred_1.keyword <= 'cripple')) OR ((hatred_1.keyword >= 'dindu') AND (hatred_1.keyword <= 'dyke')) OR ((hatred_1.keyword >= 'fatass') AND (hatred_1.keyword <= 'injun')) OR ((hatred_1.keyword >= 'japs') AND (hatred_1.keyword <= 'kike')) OR ((hatred_1.keyword >= 'midget') AND (hatred_1.keyword <= 'moroccan')) OR ((hatred_1.keyword >= 'nigger') AND (hatred_1.keyword <= 'papist')) OR ((hatred_1.keyword >= 'rag head') AND (hatred_1.keyword <= 'sandnigger')) OR ((hatred_1.keyword >= 'shemale') AND (hatred_1.keyword <= 'trans men')) OR ((hatred_1.keyword >= 'transsexual') AND (hatred_1.keyword <= 'wetback')) OR ((hatred_1.keyword >= 'whitey') AND (hatred_1.keyword <= 'yid'))) GROUP BY hatred_1.keyword,   hatred_1.state_ HAVING ((SUM(1) >= 30) AND (SUM(1) <= 100000));
