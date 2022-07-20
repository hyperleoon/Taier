package com.dtstack.taier.develop.service.develop.runner;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.dtstack.dtcenter.loader.dto.source.ISourceDTO;
import com.dtstack.dtcenter.loader.source.DataSourceType;
import com.dtstack.taier.common.enums.EScheduleJobType;
import com.dtstack.taier.common.exception.RdosDefineException;
import com.dtstack.taier.dao.domain.DevelopSelectSql;
import com.dtstack.taier.dao.domain.DevelopTaskParam;
import com.dtstack.taier.dao.domain.DevelopTaskParamShade;
import com.dtstack.taier.dao.domain.Task;
import com.dtstack.taier.develop.dto.devlop.BuildSqlVO;
import com.dtstack.taier.develop.dto.devlop.ExecuteResultVO;
import com.dtstack.taier.develop.service.datasource.impl.DatasourceService;
import com.dtstack.taier.develop.service.develop.ITaskRunner;
import com.dtstack.taier.develop.service.develop.impl.DevelopTaskParamService;
import com.dtstack.taier.develop.sql.ParseResult;
import com.dtstack.taier.develop.utils.develop.common.IDownload;
import com.dtstack.taier.develop.utils.develop.sync.job.PluginName;
import com.dtstack.taier.develop.utils.develop.sync.job.SourceType;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * @author yuebai
 * @date 2022/7/20
 */
@Component
public class SyncTaskRunner implements ITaskRunner {

    @Autowired
    protected DevelopTaskParamService developTaskParamService;

    @Autowired
    private DatasourceService datasourceService;

    private static final String JOB_ARGS_TEMPLATE = "-jobid %s -job %s";

    @Override
    public List<EScheduleJobType> support() {
        return Lists.newArrayList(EScheduleJobType.SYNC);
    }

    @Override
    public ExecuteResultVO startSqlImmediately(Long userId, Long tenantId, Long taskId, String sql, Task task, String jobId) throws Exception {
        return null;
    }

    @Override
    public void readyForTaskStartTrigger(Map<String, Object> actionParam, Long tenantId, Task task, List<DevelopTaskParamShade> taskParamsToReplace) throws Exception {
        String sql = task.getSqlText() == null ? "" : task.getSqlText();
        String taskParams = task.getTaskParams();
        JSONObject syncJob = JSON.parseObject(task.getSqlText());
        taskParams = replaceSyncParll(taskParams, parseSyncChannel(syncJob));
        String job = syncJob.getString("job");
        // 向导模式根据job中的sourceId填充数据源信息，保证每次运行取到最新的连接信息
        job = datasourceService.setJobDataSourceInfo(job, tenantId, syncJob.getIntValue("createModel"));

        developTaskParamService.checkParams(developTaskParamService.checkSyncJobParams(job), taskParamsToReplace);
        actionParam.put("job", job);
        actionParam.put("sqlText", sql);
        actionParam.put("taskParams", taskParams);
    }

    @Override
    public ExecuteResultVO selectData(Task task, DevelopSelectSql selectSql, Long tenantId, Long userId, Boolean isRoot, Integer taskType) throws Exception {
        return null;
    }

    @Override
    public ExecuteResultVO selectStatus(Task task, DevelopSelectSql selectSql, Long tenantId, Long userId, Boolean isRoot, Integer taskType) {
        return null;
    }

    @Override
    public ExecuteResultVO runLog(String jobId, Integer taskType, Long tenantId, Integer limitNum) {
        return null;
    }

    @Override
    public String scheduleRunLog(String jobId) {
        return null;
    }

    @Override
    public IDownload logDownLoad(Long tenantId, String jobId, Integer limitNum) {
        return null;
    }

    @Override
    public List<String> getAllSchema(Long tenantId, Integer taskType) {
        return null;
    }

    @Override
    public ISourceDTO getSourceDTO(Long tenantId, Long userId, Integer taskType) {
        return null;
    }

    @Override
    public String getCurrentDb(Long tenantId, Integer taskType) {
        return null;
    }

    @Override
    public BuildSqlVO buildSql(ParseResult parseResult, Long tenantId, Long userId, String database, Long taskId) {
        return null;
    }

    @Override
    public Map<String, Object> readyForSyncImmediatelyJob(Task task, Long tenantId, Boolean isRoot) {
        Map<String, Object> actionParam = Maps.newHashMap();
        try {
            String taskParams = task.getTaskParams();
            List<DevelopTaskParam> taskParamsToReplace = developTaskParamService.getTaskParam(task.getId());

            JSONObject syncJob = JSON.parseObject(task.getSqlText());
            taskParams = replaceSyncParll(taskParams, parseSyncChannel(syncJob));

            String job = syncJob.getString("job");

            // 向导模式根据job中的sourceId填充数据源信息，保证每次运行取到最新的连接信息
            job = datasourceService.setJobDataSourceInfo(job, tenantId, syncJob.getIntValue("createModel"));

            developTaskParamService.checkParams(developTaskParamService.checkSyncJobParams(job), taskParamsToReplace);

            String name = "run_sync_task_" + task.getName() + "_" + System.currentTimeMillis();
            String taskExeArgs = String.format(JOB_ARGS_TEMPLATE, name, job);
            actionParam.put("taskSourceId", task.getId());
            actionParam.put("taskType", EScheduleJobType.SYNC.getVal());
            actionParam.put("name", name);
            actionParam.put("computeType", task.getComputeType());
            actionParam.put("sqlText", "");
            actionParam.put("taskParams", taskParams);
            actionParam.put("tenantId", tenantId);
            actionParam.put("sourceType", SourceType.TEMP_QUERY.getType());
            actionParam.put("isFailRetry", false);
            actionParam.put("maxRetryNum", 0);
            actionParam.put("job", job);
            actionParam.put("taskParamsToReplace", JSON.toJSONString(taskParamsToReplace));
            DataSourceType writerDataSourceType = getSyncJobWriterDataSourceType(job);
            if (Objects.nonNull(writerDataSourceType)) {
                actionParam.put("dataSourceType", writerDataSourceType.getVal());
            }
            if (Objects.nonNull(taskExeArgs)) {
                actionParam.put("exeArgs", taskExeArgs);
            }
        } catch (Exception e) {
            throw new RdosDefineException(String.format("创建数据同步job失败: %s", e.getMessage()), e);
        }

        return actionParam;
    }

    private Integer parseSyncChannel(JSONObject syncJob) {
        //解析出并发度---sync 消耗资源是: 并发度*1
        try {
            JSONObject jobJson = syncJob.getJSONObject("job").getJSONObject("job");
            JSONObject settingJson = jobJson.getJSONObject("setting");
            JSONObject speedJson = settingJson.getJSONObject("speed");
            return speedJson.getInteger("channel");
        } catch (Exception e) {
            LOGGER.error("", e);
            //默认1
            return 1;
        }
    }

    public String replaceSyncParll(String taskParams, int parallelism) throws IOException {
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(taskParams.getBytes(StandardCharsets.UTF_8)));
        properties.put("mr.job.parallelism", parallelism);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Object, Object> tmp : properties.entrySet()) {
            sb.append(String.format("%s = %s%s", tmp.getKey(), tmp.getValue(), System.getProperty("line.separator")));
        }
        return sb.toString();
    }

    /**
     * 获取数据同步写入插件的数据源类型
     * 注意：目前只调整Inceptor类型，其他数据源类型没有出现问题，不进行变动
     *
     * @param jobStr
     * @return
     */
    public DataSourceType getSyncJobWriterDataSourceType(String jobStr) {
        JSONObject job = JSONObject.parseObject(jobStr);
        JSONObject jobContent = job.getJSONObject("job");
        JSONObject content = jobContent.getJSONArray("content").getJSONObject(0);
        JSONObject writer = content.getJSONObject("writer");
        String writerName = writer.getString("name");
        switch (writerName) {
            case PluginName.INCEPTOR_W:
                return DataSourceType.INCEPTOR;
            default:
                return null;
        }
    }
}