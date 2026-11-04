-- Task 01: non-unique SKU product lookup index (MySQL 8.0 / InnoDB).
-- Select and verify the intended schema in the client before execution.
-- Preflight: SELECT DATABASE(); SHOW INDEX FROM pms_sku_stock;
-- Do not execute if an equivalent index with product_id as its leading column exists.
-- DDL implicitly commits. Schedule an appropriate window and check metadata locks.
ALTER TABLE pms_sku_stock
    ADD INDEX idx_product_id (product_id),
    ALGORITHM=INPLACE, LOCK=NONE;
