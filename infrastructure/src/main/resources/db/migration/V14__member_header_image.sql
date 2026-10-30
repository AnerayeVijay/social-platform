ALTER TABLE member_images DROP CONSTRAINT member_images_kind_check;
ALTER TABLE member_images ADD CONSTRAINT member_images_kind_check CHECK (kind IN ('avatar', 'background', 'header'));
