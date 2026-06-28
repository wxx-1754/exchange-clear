-- ExchangeClear 数据库表结构（V1 + V2 RocketMQ 字段）
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
    download_count BIGINT NOT NULL DEFAULT 0 COMMENT '下载次数',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_file_biz (settle_date, member_id, file_type, version),
    UNIQUE KEY uk_file_no (file_no),
    KEY idx_member_date (member_id, settle_date),
    KEY idx_status (status),
    KEY idx_task_no (task_no)
) COMMENT='结算文件元数据表';
