-- 类型与版本筛选后按稳定游标输出，不对应用全部登记排序或在 JVM 分页。
CREATE INDEX app_symbol_file_version_page_idx
 ON app_symbol_file(app_id,symbol_type,app_version,updated_at DESC,symbol_id DESC)
 WHERE deleted_at IS NULL;
