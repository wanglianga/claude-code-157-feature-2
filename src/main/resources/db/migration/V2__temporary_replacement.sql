-- 陪护人临时替换（原陪护人夜间发烧等紧急场景）
-- 在 replacement_record 上补充"交接时间轴 + 护士站核验位"，并新增巡查记录、看护提醒两张表。
-- 若旧陪护已离院而新陪护未到，患者处于"短时无人陪护"状态，护士站据此安排巡查；
-- 空窗期间的看护提醒（进食/翻身/夜间观察）由护士站临时承担，新陪护到位后转为家属已接手。

-- 1) replacement_record 扩展为两阶段交接模型（常规替换 mode=REGULAR 沿用原同步流程）
ALTER TABLE replacement_record ADD COLUMN mode                  VARCHAR(16) NOT NULL DEFAULT 'REGULAR';
ALTER TABLE replacement_record ADD COLUMN old_escort_left_at    TIMESTAMP;   -- 旧陪护离院（交接开始、进入空窗）
ALTER TABLE replacement_record ADD COLUMN new_escort_arrived_at TIMESTAMP;   -- 新陪护到位（交接结束）
ALTER TABLE replacement_record ADD COLUMN handover_completed_at TIMESTAMP;   -- 交接完成时间（= 新陪护到位确认时间）
ALTER TABLE replacement_record ADD COLUMN relation_verified     BOOLEAN NOT NULL DEFAULT FALSE; -- 与患者关系经护士站核验
ALTER TABLE replacement_record ADD COLUMN identity_verified     BOOLEAN NOT NULL DEFAULT FALSE; -- 新陪护人身份经现场核验
ALTER TABLE replacement_record ADD COLUMN health_verified       BOOLEAN NOT NULL DEFAULT FALSE; -- 健康承诺/筛查经核验
ALTER TABLE replacement_record ADD COLUMN night_care_approved   BOOLEAN NOT NULL DEFAULT FALSE; -- 夜间陪护专项批准
ALTER TABLE replacement_record ADD COLUMN completed_by          VARCHAR(64);
ALTER TABLE replacement_record ADD COLUMN cancelled_at          TIMESTAMP;
ALTER TABLE replacement_record ADD COLUMN cancelled_by          VARCHAR(64);
ALTER TABLE replacement_record ADD COLUMN cancel_reason         VARCHAR(512);

-- TEMPORARY 模式新增的中间状态：APPROVAL_PENDING=待护士核验审批, OLD_LEFT=旧人已离院(空窗),
-- NEW_ARRIVED=新人已到位待身份复核, COMPLETED=交接完成新证已签发, CANCELLED=交接取消
-- （REGULAR 模式仍只使用 PENDING/APPROVED/REJECTED）

CREATE INDEX idx_replacement_status_mode ON replacement_record (status, mode);
CREATE INDEX idx_replacement_case_mode ON replacement_record (case_id, mode);

-- 2) 巡查记录：空窗期护士站安排的巡查
CREATE TABLE patrol_record (
    id             BIGSERIAL PRIMARY KEY,
    case_id        BIGINT NOT NULL REFERENCES escort_case (id),
    replacement_id BIGINT NOT NULL REFERENCES replacement_record (id),
    ward_code      VARCHAR(64) NOT NULL,
    bed_no         VARCHAR(32),
    scheduled_at   TIMESTAMP NOT NULL,               -- 计划巡查时间
    scheduled_by   VARCHAR(64) NOT NULL,
    status         VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED', -- SCHEDULED / DONE / CANCELLED
    result         VARCHAR(512),                     -- 巡查结果（患者情况）
    patrolled_at   TIMESTAMP,                        -- 实际巡查时间
    patrolled_by   VARCHAR(64),
    created_at     TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_patrol_replacement ON patrol_record (replacement_id);
CREATE INDEX idx_patrol_ward_status ON patrol_record (ward_code, status, scheduled_at);

-- 3) 看护提醒：进食/翻身/夜间观察等；空窗期 NURSE 承担，到位后转交 FAMILY
CREATE TABLE care_reminder (
    id             BIGSERIAL PRIMARY KEY,
    case_id        BIGINT NOT NULL REFERENCES escort_case (id),
    replacement_id BIGINT REFERENCES replacement_record (id), -- 常规陪护期创建的提醒为空
    permit_id      BIGINT REFERENCES escort_permit (id),      -- 家属接手后关联其陪护证
    ward_code      VARCHAR(64) NOT NULL,
    type           VARCHAR(32) NOT NULL,               -- FEEDING / TURNING / NIGHT_OBSERVATION / OTHER
    content        VARCHAR(512) NOT NULL,
    remind_at      TIMESTAMP NOT NULL,                 -- 计划提醒时间
    status         VARCHAR(24) NOT NULL DEFAULT 'PENDING_NURSE',
    -- PENDING_NURSE=待护士处理(空窗期) / NURSE_DONE=护士已完成 / FAMILY_TAKEN_OVER=家属已接手 /
    -- FAMILY_DONE=家属已完成 / CANCELLED=取消
    created_by     VARCHAR(64) NOT NULL,
    created_at     TIMESTAMP NOT NULL DEFAULT now(),
    taken_over_at  TIMESTAMP,                          -- 家属接手时间
    taken_over_by  VARCHAR(64),                        -- 接手的新陪护人姓名
    taken_over_permit_id BIGINT REFERENCES escort_permit (id),
    nurse_done_at  TIMESTAMP,
    nurse_done_by  VARCHAR(64),
    family_done_at TIMESTAMP,
    family_done_by VARCHAR(64),
    cancelled_at   TIMESTAMP,
    cancelled_by   VARCHAR(64)
);
CREATE INDEX idx_reminder_case ON care_reminder (case_id, status);
CREATE INDEX idx_reminder_replacement ON care_reminder (replacement_id, status);
CREATE INDEX idx_reminder_ward_status ON care_reminder (ward_code, status, remind_at);
