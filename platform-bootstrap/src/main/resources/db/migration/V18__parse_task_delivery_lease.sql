-- V18: Relay 多实例投递租约。SENDING 超时后可由其他实例接管，避免永久卡死。

ALTER TABLE parse_tasks
    ADD COLUMN delivery_leased_by VARCHAR(64) NULL AFTER delivery_error,
    ADD COLUMN delivery_lease_until TIMESTAMP NULL AFTER delivery_leased_by,
    ADD INDEX idx_parse_delivery_lease
        (delivery_status, delivery_lease_until);
