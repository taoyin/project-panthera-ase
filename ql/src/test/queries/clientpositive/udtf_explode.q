DESCRIBE FUNCTION explode;
DESCRIBE FUNCTION EXTENDED explode;

EXPLAIN EXTENDED SELECT explode(array(1,2,3)) AS myCol FROM src LIMIT 3;
EXPLAIN EXTENDED SELECT a.myCol, count(1) FROM (SELECT explode(array(1,2,3)) AS myCol FROM src LIMIT 3) a GROUP BY a.myCol;

SELECT explode(array(1,2,3)) AS myCol FROM src LIMIT 3;
SELECT explode(array(1,2,3)) AS (myCol) FROM src LIMIT 3;
SELECT a.myCol, count(1) FROM (SELECT explode(array(1,2,3)) AS myCol FROM src LIMIT 3) a GROUP BY a.myCol;

EXPLAIN SELECT explode(map(1,'one',2,'two',3,'three')) as (myKey,myVal) FROM src LIMIT 3;
EXPLAIN EXTENDED SELECT a.myKey, a.myVal, count(1) FROM (SELECT explode(map(1,'one',2,'two',3,'three')) as (myKey,myVal) FROM src LIMIT 3) a GROUP BY a.myKey, a.myVal;

SELECT explode(map(1,'one',2,'two',3,'three')) as (myKey,myVal) FROM src LIMIT 3;
SELECT a.myKey, a.myVal, count(1) FROM (SELECT explode(map(1,'one',2,'two',3,'three')) as (myKey,myVal) FROM src LIMIT 3) a GROUP BY a.myKey, a.myVal;