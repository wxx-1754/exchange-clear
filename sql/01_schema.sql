-- ExchangeClear 数据库表结构（V1 + V2 RocketMQ 字段 + V5 发布/重发/补偿字段）
-- 由 docker-compose 中 mysql 容器首次启动时通过 /docker-entrypoint-initdb.d 自动执行。
-- 与 settle-app/src/main/resources/sql/01_schema.sql 保持一致，作为微服务集群的统一建表脚本。

CREATE TABLE IF NOT EXISTS settle_member (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    member_id VARCHAR(32) NOT NULL COMMENT '会员编号',
    member_name VARCHAR(128) NOT NULL COMMENT '会员名称',
    status VARCHAR(32) NOT NULL COMMENT '状态：ACTIVE/INACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_member_id (member_id)
) COMMENT='会员信息表';

CREATE TABLE IF NOT EXISTS trade_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    settle_date DATE NOT NULL COMMENT '结算日期',
    member_id VARCHAR(32) NOT NULL COMMENT '会员编号',
    trade_no VARCHAR(64) NOT NULL COMMENT '成交编号',
    product_id VARCHAR(32) NOT NULL COMMENT '产品编号',
    contract_id VARCHAR(32) NOT NULL COMMENT '合约编号',
    direction VARCHAR(16) NOT NULL COMMENT '买卖方向：BUY/SELL',
    price DECIMAL(18, 4) NOT NULL COMMENT '成交价格',
    volume INT NOT NULL COMMENT '成交数量',
    amount DECIMAL(18, 4) NOT NULL COMMENT '成交金额',
    trade_time DATETIME NOT NULL COMMENT '成交时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    KEY idx_settle_member_id (settle_date, member_id, id),
    KEY idx_trade_no (trade_no)
) COMMENT='模拟成交数据表';

CREATE TABLE IF NOT EXISTS settle_file_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    task_no VARCHAR(64) NOT NULL COMMENT '任务编号',
    settle_date DATE NOT NULL COMMENT '结算日期',
    member_id VARCHAR(32) NOT NULL COMMENT '会员编号',
    file_type VARCHAR(32) NOT NULL COMMENT '文件类型：TRADE/FUND/POSITION',
    version INT NOT NULL DEFAULT 1 COMMENT '文件版本',
    status VARCHAR(32) NOT NULL COMMENT '任务状态：INIT/SENT/GENERATING/GENERATED/FAILED/SEND_FAILED',
    retry_count INT NOT NULL DEFAULT 0 COMMENT '业务重试次数',
    max_retry_count INT NOT NULL DEFAULT 3 COMMENT '最大业务重试次数',
    last_message_id VARCHAR(128) DEFAULT NULL COMMENT '最后一次投递的消息ID',
    last_send_time DATETIME DEFAULT NULL COMMENT '最后一次发送MQ时间',
    last_consume_time DATETIME DEFAULT NULL COMMENT '最后一次消费时间',
    error_message VARCHAR(2000) DEFAULT NULL COMMENT '错误信息',
    start_time DATETIME DEFAULT NULL COMMENT '开始时间',
    end_time DATETIME DEFAULT NULL COMMENT '结束时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_task_biz (settle_date, member_id, file_type, version),
    UNIQUE KEY uk_task_no (task_no),
    KEY idx_status (status),
    KEY idx_settle_date (settle_date),
    KEY idx_member_id (member_id)
) COMMENT='结算文件生成任务表';

CREATE TABLE IF NOT EXISTS settle_file (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    file_no VARCHAR(64) NOT NULL COMMENT '文件编号',
    task_no VARCHAR(64) NOT NULL COMMENT '任务编号',
    settle_date DATE NOT NULL COMMENT '结算日期',
    member_id VARCHAR(32) NOT NULL COMMENT '会员编号',
    file_type VARCHAR(32) NOT NULL COMMENT '文件类型',
    file_name VARCHAR(255) NOT NULL COMMENT '文件名称',
    file_size BIGINT NOT NULL COMMENT '文件大小',
    file_md5 VARCHAR(64) NOT NULL COMMENT '文件MD5',
    storage_bucket VARCHAR(128) NOT NULL COMMENT '对象存储桶',
    storage_path VARCHAR(512) NOT NULL COMMENT '对象存储路径',
    version INT NOT NULL DEFAULT 1 COMMENT '文件版本',
    status VARCHAR(32) NOT NULL COMMENT '文件状态',
    publish_time DATETIME DEFAULT NULL COMMENT '发布时间，第一阶段可为空',
    revoke_time DATETIME DEFAULT NULL COMMENT '撤销时间',
    reissue_time DATETIME DEFAULT NULL COMMENT '重发时间',
    status_reason VARCHAR(1000) DEFAULT NULL COMMENT '状态变更原因',
    download_count BIGINT NOT NULL DEFAULT 0 COMMENT '下载次数',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_file_biz (settle_date, member_id, file_type, version),
    UNIQUE KEY uk_file_no (file_no),
    KEY idx_settle_file_member_date (member_id, settle_date),
    KEY idx_settle_file_publish (settle_date, file_type, version, status),
    KEY idx_status (status),
    KEY idx_task_no (task_no)
) COMMENT='结算文件元数据表';

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

CREATE TABLE IF NOT EXISTS file_download_audit (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    audit_no VARCHAR(64) NOT NULL COMMENT '审计编号',
    request_id VARCHAR(64) DEFAULT NULL COMMENT '请求ID',
    action VARCHAR(32) NOT NULL COMMENT '行为类型：CREATE_TOKEN/DOWNLOAD_FILE',
    download_status VARCHAR(32) NOT NULL COMMENT '下载结果状态',
    fail_reason VARCHAR(1000) DEFAULT NULL COMMENT '失败原因',
    file_no VARCHAR(64) DEFAULT NULL COMMENT '文件编号',
    file_name VARCHAR(255) DEFAULT NULL COMMENT '文件名称',
    file_type VARCHAR(32) DEFAULT NULL COMMENT '文件类型',
    settle_date DATE DEFAULT NULL COMMENT '结算日期',
    member_id VARCHAR(32) DEFAULT NULL COMMENT '会员编号',
    version INT DEFAULT NULL COMMENT '文件版本',
    client_ip VARCHAR(64) DEFAULT NULL COMMENT '客户端IP',
    user_agent VARCHAR(512) DEFAULT NULL COMMENT 'User-Agent',
    token_digest VARCHAR(128) DEFAULT NULL COMMENT 'Token摘要，不保存完整Token',
    file_size BIGINT DEFAULT NULL COMMENT '文件大小',
    download_bytes BIGINT DEFAULT NULL COMMENT '实际下载字节数',
    start_time DATETIME DEFAULT NULL COMMENT '下载开始时间',
    end_time DATETIME DEFAULT NULL COMMENT '下载结束时间',
    cost_ms BIGINT DEFAULT NULL COMMENT '下载耗时毫秒',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_audit_no (audit_no),
    KEY idx_member_date (member_id, settle_date),
    KEY idx_file_no (file_no),
    KEY idx_status_created (download_status, created_at),
    KEY idx_client_ip_created (client_ip, created_at),
    KEY idx_created_at (created_at)
) COMMENT='文件下载审计表';
