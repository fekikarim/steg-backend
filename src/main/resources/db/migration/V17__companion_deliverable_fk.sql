-- Migration V17: Add missing foreign key constraint from deliverable_versions to file_assets
ALTER TABLE deliverable_versions
    ADD CONSTRAINT fk_deliv_version_file_asset
    FOREIGN KEY (file_asset_id) REFERENCES file_assets(id) ON DELETE RESTRICT;
