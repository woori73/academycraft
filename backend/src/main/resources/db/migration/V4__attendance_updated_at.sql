-- Keep V1-V3 checksums intact; match the existing attendance entity.
ALTER TABLE rea_attendance_events ADD COLUMN updated_at DATETIME(6) NULL;
