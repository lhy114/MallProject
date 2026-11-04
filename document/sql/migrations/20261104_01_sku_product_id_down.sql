-- Roll back only the index created by the matching up script.
-- Verify schema, index name and columns first. DDL implicitly commits.
ALTER TABLE pms_sku_stock
    DROP INDEX idx_product_id,
    ALGORITHM=INPLACE, LOCK=NONE;
