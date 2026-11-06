-- Which services each person does. Someone with no rows here does all of them, which is
-- how it was before and how most small places work; rows narrow it down (only Bia colors).
CREATE TABLE professional_service (
    professional_id BIGINT NOT NULL REFERENCES professional (id) ON DELETE CASCADE,
    service_id      BIGINT NOT NULL REFERENCES service (id) ON DELETE CASCADE,
    PRIMARY KEY (professional_id, service_id)
);
