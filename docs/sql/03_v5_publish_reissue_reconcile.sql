-- ExchangeClear V5 发布、撤销、重发与一致性补偿增量脚本

ALTER TABLE settle_file
    ADD COLUMN revoke_time DATETIME DEFAULT NULL COMMENT '撤销时间',
    ADD COLUMN reissue_time DATETIME DEFAULT NULL COMMENT '重发时间',
    ADD COLUMN status_reason VARCHAR(1000) DEFAULT NULL COMMENT '状态变更原因';

CREATE INDEX idx_settle_file_publish
    ON settle_file(settle_date, file_type, version, status);

CREATE INDEX idx_settle_file_member_date
    ON settle_file(member_id, settle_date);

CREATE TABLE IF NOT EXISTS file_publish_batch (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    batch_no VARCHAR(64) NOT NULL COMMENT '发布批次号',
    settle_date DATE NOT NULL COMMENT '结算日期',
    file_type VARCHAR(32) DEFAULT NULL COMMENT '文件类型，为空表示全部类型',
    version INT DEFAULT NULL COMMENT '版本，为空表示当前版本',
    status VARCHAR(32) NOT NULL COMMENT '发布批次状态：INIT/PUBLISHING/SUCCESS/FAILED',
    total_count INT NOT NULL DEFAULT 0 COMMENT '应发布文件数量',
    success_count INT NOT NULL DEFAULT 0 COMMENT '成功发布文件数量',
    failed_count INT NOT NULL DEFAULT 0 COMMENT '失败文件数量',
    operator VARCHAR(64) DEFAULT NULL COMMENT '操作人',
    error_message VARCHAR(2000) DEFAULT NULL COMMENT '错误信息',
    start_time DATETIME DEFAULT NULL COMMENT '开始时间',
    end_time DATETIME DEFAULT NULL COMMENT '结束时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_batch_no (batch_no),
    KEY idx_settle_date (settle_date),
    KEY idx_status (status)
) COMMENT='文件发布批次表';

CREATE TABLE IF NOT EXISTS file_status_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    file_no VARCHAR(64) NOT NULL COMMENT '文件编号',
    settle_date DATE NOT NULL COMMENT '结算日期',
    member_id VARCHAR(32) NOT NULL COMMENT '会员编号',
    file_type VARCHAR(32) NOT NULL COMMENT '文件类型',
    version INT NOT NULL COMMENT '版本',
    before_status VARCHAR(32) DEFAULT NULL COMMENT '变更前状态',
    after_status VARCHAR(32) NOT NULL COMMENT '变更后状态',
    operation_type VARCHAR(32) NOT NULL COMMENT '操作类型：GENERATE/PUBLISH/REVOKE/REISSUE',
    batch_no VARCHAR(64) DEFAULT NULL COMMENT '批次号',
    operator VARCHAR(64) DEFAULT NULL COMMENT '操作人',
    reason VARCHAR(1000) DEFAULT NULL COMMENT '原因',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    KEY idx_file_no (file_no),
    KEY idx_settle_date (settle_date),
    KEY idx_batch_no (batch_no)
) COMMENT='文件状态变更记录表';

CREATE TABLE IF NOT EXISTS local_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    message_id VARCHAR(64) NOT NULL COMMENT '消息ID',
    topic VARCHAR(128) NOT NULL COMMENT 'Topic',
    tag VARCHAR(64) DEFAULT NULL COMMENT 'Tag',
    biz_key VARCHAR(128) NOT NULL COMMENT '业务键',
    message_type VARCHAR(64) NOT NULL COMMENT '消息类型',
    payload TEXT NOT NULL COMMENT '消息内容JSON',
    status VARCHAR(32) NOT NULL COMMENT '状态：INIT/SENT/FAILED',
    retry_count INT NOT NULL DEFAULT 0 COMMENT '重试次数',
    max_retry_count INT NOT NULL DEFAULT 5 COMMENT '最大重试次数',
    next_retry_time DATETIME DEFAULT NULL COMMENT '下次重试时间',
    error_message VARCHAR(2000) DEFAULT NULL COMMENT '错误信息',
    sent_time DATETIME DEFAULT NULL COMMENT '发送成功时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_message_id (message_id),
    KEY idx_status_retry_time (status, next_retry_time),
    KEY idx_biz_key (biz_key),
    KEY idx_message_type (message_type)
) COMMENT='本地消息表';

CREATE TABLE IF NOT EXISTS file_reissue_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    reissue_no VARCHAR(64) NOT NULL COMMENT '重发编号',
    old_file_no VARCHAR(64) NOT NULL COMMENT '旧文件编号',
    new_file_no VARCHAR(64) DEFAULT NULL COMMENT '新文件编号',
    settle_date DATE NOT NULL COMMENT '结算日期',
    member_id VARCHAR(32) NOT NULL COMMENT '会员编号',
    file_type VARCHAR(32) NOT NULL COMMENT '文件类型',
    old_version INT NOT NULL COMMENT '旧版本',
    new_version INT NOT NULL COMMENT '新版本',
    status VARCHAR(32) NOT NULL COMMENT '状态：INIT/GENERATING/GENERATED/PUBLISHED/FAILED',
    reason VARCHAR(1000) DEFAULT NULL COMMENT '重发原因',
    operator VARCHAR(64) DEFAULT NULL COMMENT '操作人',
    error_message VARCHAR(2000) DEFAULT NULL COMMENT '错误信息',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_reissue_no (reissue_no),
    KEY idx_old_file_no (old_file_no),
    KEY idx_new_file_no (new_file_no),
    KEY idx_settle_member_type (settle_date, member_id, file_type)
) COMMENT='文件重发记录表';
