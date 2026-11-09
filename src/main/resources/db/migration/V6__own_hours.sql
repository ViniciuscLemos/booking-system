-- Hours of one person, inside the place's: Bia only does afternoons, Caio is off on Mondays.
-- Someone with no rows here works whenever the place is open, like before.
CREATE TABLE professional_hours (
    id              BIGSERIAL PRIMARY KEY,
    professional_id BIGINT   NOT NULL REFERENCES professional (id) ON DELETE CASCADE,
    day_of_week     SMALLINT NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    starts          TIME     NOT NULL,
    ends            TIME     NOT NULL,
    CHECK (starts < ends)
);
CREATE INDEX professional_hours_who ON professional_hours (professional_id);
