-- 陪护人临时替换(夜间发烧等紧急场景):
--   旧陪护离院 -> 患者短时无人陪护(护士站可见并安排巡查)
--   -> 护士站核验新陪护身份/健康承诺/亲属关系并签发新证 -> 新陪护到位交接(记录交接时间)
-- 临时替换期间护士站可临时创建看护提醒(进食/翻身/夜间观察),
-- 新陪护到位后提醒自动转为"家属已接手"并保留交接时间。

-- 1) escort_case: 患者当前陪护状态(是否处于无人陪护间隙)
ALTER TABLE escort_case
    ADD COLUMN care_status        VARCHAR(24) NOT NULL DEFAULT 'WITH_ESCORT',  -- WITH_ESCORT / UNATTENDED
    ADD COLUMN unattended_since   TIMESTAMP,
    ADD COLUMN pending_replacement_id BIGINT REFERENCES replacement_record (id);
CREATE INDEX idx_case_unattended ON escort_case (ward_code, care_status);

-- 2) replacement_record: 临时替换标记 / 核验信息 / 离院与到位交接时间
ALTER TABLE replacement_record
    ADD COLUMN temporary          BOOLEAN NOT NULL DEFAULT FALSE,
    -- 护士站核验结果
    ADD COLUMN identity_verified       BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN identity_verified_by    VARCHAR(64),
    ADD COLUMN identity_verified_at    TIMESTAMP,
    ADD COLUMN health_promise_accepted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN relation_verified       BOOLEAN NOT NULL DEFAULT FALSE,
    -- 交接时间点
    ADD COLUMN old_left_at        TIMESTAMP,   -- 旧陪护离院时间
    ADD COLUMN new_arrived_at     TIMESTAMP,   -- 新陪护到位交接时间
    ADD COLUMN gap_minutes        INT,         -- 无人陪护时长(分钟), 到位时回填
    ADD COLUMN canceled_at        TIMESTAMP,
    ADD COLUMN canceled_by        VARCHAR(64),
    ADD COLUMN cancel_reason      VARCHAR(512);
CREATE INDEX idx_replacement_status ON replacement_record (status, temporary);

-- 3) 看护提醒: 无人陪护/临时替换期间护士站临时创建, 新陪护到位后自动转家属接手
CREATE TABLE care_reminder (
    id               BIGSERIAL PRIMARY KEY,
    case_id          BIGINT NOT NULL REFERENCES escort_case (id),
    ward_code        VARCHAR(64) NOT NULL,
    replacement_id   BIGINT REFERENCES replacement_record (id),
    type             VARCHAR(24) NOT NULL,        -- FEEDING / TURNING / NIGHT_WATCH
    note             VARCHAR(512),
    due_at           TIMESTAMP NOT NULL,          -- 计划执行/提醒时间
    -- NURSE_PENDING 护士待巡查执行
    -- NURSE_DONE    护士已执行一次(新陪护仍未到位, 可继续跟进)
    -- FAMILY_TAKEN_OVER 新陪护到位后自动转为家属已接手
    status           VARCHAR(24) NOT NULL DEFAULT 'NURSE_PENDING',
    created_by       VARCHAR(64) NOT NULL,
    created_at       TIMESTAMP NOT NULL DEFAULT now(),
    nurse_done_at    TIMESTAMP,
    nurse_done_by    VARCHAR(64),
    taken_over_at    TIMESTAMP,                   -- 家属接手时间(=新陪护到位交接时间)
    taken_over_by    VARCHAR(64)                  -- 接手的新陪护人
);
CREATE INDEX idx_reminder_ward_status ON care_reminder (ward_code, status);
CREATE INDEX idx_reminder_case ON care_reminder (case_id, created_at);
