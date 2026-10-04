-- ============================================================
-- V2__partition_procedures.sql  分区维护存储过程
-- 唯一事实源：落地文档设计/02_总体设计_架构与数据.md §四「分区维护存储过程」
-- 说明：Flyway MySQL 支持 DELIMITER 指令，用于包裹含分号的存储过程体。
-- 纪律：V2.1.1 基线仅定义 sp_add_raw_partition；
--       sp_drop_raw_partition_before 属 V2.2 待评审整改项 P2-08（F-DB-05 解除依据），
--       本轮不回写基线（见 修订版 D-2），故此处不建 DROP 过程，W0.2 出口条件仅需
--       "31 表 DDL + 本过程在 MySQL 8.0.3x 实跑通过"。
-- ============================================================

DELIMITER //
CREATE PROCEDURE `sp_add_raw_partition`(IN p_month CHAR(7))
BEGIN
  DECLARE p_name  VARCHAR(16) DEFAULT CONCAT('p_', REPLACE(p_month,'-',''));
  DECLARE p_bound VARCHAR(10) DEFAULT DATE_FORMAT(
      STR_TO_DATE(CONCAT(p_month,'-01'),'%Y-%m-%d') + INTERVAL 1 MONTH,'%Y-%m-%d');
  SET @sql = CONCAT('ALTER TABLE m3_raw_record REORGANIZE PARTITION p_max INTO (',
    'PARTITION ', p_name, ' VALUES LESS THAN (''', p_bound, '''),',
    'PARTITION p_max VALUES LESS THAN (MAXVALUE))');
  PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
END //
DELIMITER ;
