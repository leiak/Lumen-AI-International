-- Default business calendar: Mon-Fri 9:00-18:00 (tenant_id=0)
INSERT INTO t_business_calendar (tenant_id, weekday, start_time, end_time) VALUES
  (0, 1, '09:00:00', '18:00:00'),
  (0, 2, '09:00:00', '18:00:00'),
  (0, 3, '09:00:00', '18:00:00'),
  (0, 4, '09:00:00', '18:00:00'),
  (0, 5, '09:00:00', '18:00:00');