-- Dev and test only. What the Kausani contract was bid at.
--
-- V900 gives the project a contract value and a budget and stops there, which was fair while
-- the screens led with the estimate. They lead with the quoted cost now — it is what the work
-- actually pays — and a demo whose only project shows a dash under "Quoted value" and totals
-- an order book of nothing is a demo of the feature failing.
--
-- 12.5% below the estimate, which is the sort of figure a Kausani job is actually won at, and
-- the two columns agree with the arithmetic the project form does: 18500000 x (1 - 0.125).

UPDATE projects
   SET quoted_percent = -12.50,
       quoted_cost = 16187500.00
 WHERE id = '30000000-0000-0000-0000-000000000001';
