SELECT hatred_1.keyword AS keyword,   hatred_1.state_ AS state_ FROM hatred_1 WHERE ((hatred_1.keyword IN ('beaner', 'bimbo', 'camel jockey', 'chicks with dicks', 'coon', 'dyke', 'hag', 'hick', 'hillbillies', 'hillbilly', 'honky', 'lesbo', 'man in a dress', 'men in dresses', 'nigger', 'paki', 'porch monkey', 'rag head', 'raghead', 'redneck', 'retard', 'sand nigger', 'sandnigger', 'shemale', 'spic', 'towel head', 'towelhead', 'trailer trash', 'trannies', 'tranny', 'twat', 'wetback')) AND (((hatred_1.state_ >= 'AK') AND (hatred_1.state_ <= 'CT')) OR ((hatred_1.state_ >= 'DE') AND (hatred_1.state_ <= 'WY')))) GROUP BY hatred_1.keyword,   hatred_1.state_;