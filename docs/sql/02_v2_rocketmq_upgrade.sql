ALTER TABLE settle_file_task
ADD COLUMN retry_count INT NOT NULL DEFAULT 0 COMMENT '业务重试次数' AFTER status,
ADD COLUMN max_retry_count INT NOT NULL DEFAULT 3 COMMENT '最大业务重试次数' AFTER retry_count,
ADD COLUMN last_message_id VARCHAR(128) DEFAULT NULL COMMENT '最后一次投递的消息ID' AFTER max_retry_count,
ADD COLUMN last_send_time DATETIME DEFAULT NULL COMMENT '最后一次发送MQ时间' AFTER last_message_id,
ADD COLUMN last_consume_time DATETIME DEFAULT NULL COMMENT '最后一次消费时间' AFTER last_send_time;
