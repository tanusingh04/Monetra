-- V8__fraud_alerts_notifications.sql: Fraud Alerts & Notifications System

CREATE TABLE IF NOT EXISTS notifications (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    type VARCHAR(50) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    recipient_user_id UUID REFERENCES users(id),
    recipient_role VARCHAR(30),
    title VARCHAR(255) NOT NULL,
    message TEXT NOT NULL,
    ref_type VARCHAR(50),
    ref_id VARCHAR(100),
    status VARCHAR(20) NOT NULL DEFAULT 'UNREAD',
    channel VARCHAR(30) NOT NULL DEFAULT 'IN_APP',
    delivery_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    delivered_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_notifications_recipient_user ON notifications(recipient_user_id, status);
CREATE INDEX IF NOT EXISTS idx_notifications_recipient_role ON notifications(recipient_role, status);
CREATE INDEX IF NOT EXISTS idx_notifications_event_id ON notifications(event_id);
CREATE INDEX IF NOT EXISTS idx_notifications_severity ON notifications(severity);

CREATE UNIQUE INDEX IF NOT EXISTS uq_notifications_dedup 
ON notifications(event_id, type, COALESCE(recipient_user_id, '00000000-0000-0000-0000-000000000000'::uuid), COALESCE(recipient_role, 'NONE'));
