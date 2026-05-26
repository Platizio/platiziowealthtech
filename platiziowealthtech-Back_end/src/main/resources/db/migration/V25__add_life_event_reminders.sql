ALTER TABLE investors
    ADD COLUMN IF NOT EXISTS anniversary_date date,
    ADD COLUMN IF NOT EXISTS goal_maturity_date date;

CREATE TABLE IF NOT EXISTS life_event_reminders (
    id uuid primary key,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    distributor_id uuid not null,
    investor_id uuid not null,
    event_type varchar(50) not null,
    event_date date not null,
    reminder_date date not null,
    status varchar(50) not null,
    title varchar(250) not null,
    message varchar(1000) not null
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_life_event_reminders_investor_event
    ON life_event_reminders (investor_id, event_type, event_date);

CREATE INDEX IF NOT EXISTS idx_life_event_reminders_dashboard
    ON life_event_reminders (distributor_id, status, event_date);

CREATE INDEX IF NOT EXISTS idx_investors_life_event_dates
    ON investors (distributor_id, date_of_birth, anniversary_date, goal_maturity_date)
    WHERE is_deleted = false
      AND (
          date_of_birth IS NOT NULL
          OR anniversary_date IS NOT NULL
          OR goal_maturity_date IS NOT NULL
      );
