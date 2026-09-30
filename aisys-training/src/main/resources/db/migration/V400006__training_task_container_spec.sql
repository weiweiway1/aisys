-- V400006 训练任务增加 container_spec 列（JSONB）。
-- 存放「每个模型是一个容器」所需的容器描述：{imageName,imageTarRelPath,datasetRelPath,datasetFormat,taskMode}。
-- 幂等。
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'training_task' AND column_name = 'container_spec'
    ) THEN
        ALTER TABLE training_task ADD COLUMN container_spec JSONB;
    END IF;
END $$;

COMMENT ON COLUMN training_task.container_spec IS '容器描述：{imageName,imageTarRelPath,datasetRelPath,datasetFormat,taskMode}';
