package com.baiyi.opscloud.facade.impl;

import com.baiyi.opscloud.bo.CreateCloudInstanceBO;
import com.baiyi.opscloud.common.util.BeanCopierUtils;
import com.baiyi.opscloud.common.util.TimeUtils;
import com.baiyi.opscloud.decorator.CloudInstanceTaskDecorator;
import com.baiyi.opscloud.domain.generator.OcCloudInstanceTask;
import com.baiyi.opscloud.domain.generator.OcCloudInstanceTaskMember;
import com.baiyi.opscloud.domain.generator.OcCloudVpcVswitch;
import com.baiyi.opscloud.domain.vo.cloud.OcCloudInstanceTaskVO;
import com.baiyi.opscloud.domain.vo.cloud.OcCloudInstanceTemplateVO;
import com.baiyi.opscloud.facade.CloudInstanceTaskFacade;
import com.baiyi.opscloud.handler.CreateInstanceTaskHandler;
import com.baiyi.opscloud.service.cloud.OcCloudInstanceTaskMemberService;
import com.baiyi.opscloud.service.cloud.OcCloudInstanceTaskService;
import com.baiyi.opscloud.service.cloud.OcCloudVpcVswitchService;
import com.baiyi.opscloud.service.server.OcServerService;
import com.google.common.collect.Lists;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Date;
import java.util.List;

/**
 * @Author baiyi
 * @Date 2020/3/30 11:40 上午
 * @Version 1.0
 */
@Service
public class CloudInstanceTaskFacadeImpl implements CloudInstanceTaskFacade {

    @Resource
    private OcCloudInstanceTaskService ocCloudInstanceTaskService;

    @Resource
    private OcCloudInstanceTaskMemberService ocCloudInstanceTaskMemberService;

    @Resource
    private OcCloudVpcVswitchService ocCloudVpcVswitchService;

    @Resource
    private OcServerService ocServerService;

    @Resource
    private CreateInstanceTaskHandler createInstanceTaskHandler;

    @Resource
    private CloudInstanceTaskDecorator cloudInstanceTaskDecorator;

    // 创建实例
    public static final String TASK_STATUS_CREATE_INSTANCE = "CREATE_INSTANCE";
    // 分配公网地址
    public static final String TASK_STATUS_ALLOCATE_PUBLIC_IP_ADDRESS = "ALLOCATE_PUBLIC_IP_ADDRESS";
    // 实例正在启动
    public static final String TASK_STATUS_STARTING = "STARTING";
    // 实例正常运行
    public static final String TASK_STATUS_RUNNING = "RUNNING";

    public static final int TASK_TIMEOUT_MINUTE = 5;

    @Override
    @Async(value = "taskExecutorCloudInstance")
    public void doCreateInstanceTask(OcCloudInstanceTask ocCloudInstanceTask, CreateCloudInstanceBO createCloudInstanceBO) {
        List<String> vswitchIds = getVswitchIdList(createCloudInstanceBO);
        int taskId = ocCloudInstanceTask.getId();
        // 当前可用区下的虚拟交换机地址池ip不足
        if (vswitchIds.isEmpty() || vswitchIds.size() < createCloudInstanceBO.getCreateCloudInstance().getCreateSize()) {
            saveCreateInstanceTaskError(ocCloudInstanceTask, "当前可用区下的虚拟交换机地址池ip不足");
            return;
        }
        Date taskStartDate = new Date();
        int maxSerialNumber = ocServerService.queryOcServerMaxSerialNumber(createCloudInstanceBO.getCreateCloudInstance().getServerGroupId());

        // 任务总时长限制 每实例5分钟上限  （10 + (n-1)*2 )
        boolean isTaskFinalized = false;

        while (!isTaskFinalized) {
            int taskMemberSize = ocCloudInstanceTaskMemberService.countOcCloudInstanceTaskMemberByTaskId(taskId);
            // 创建实例
            if (taskMemberSize < createCloudInstanceBO.getCreateCloudInstance().getCreateSize()) {
                // 执行创建实例
                int seq = taskMemberSize + 1;
                String vswitchId = vswitchIds.get(0);
                if (createInstanceTaskHandler.createInstanceHandler(taskId, createCloudInstanceBO, maxSerialNumber, seq, vswitchId))
                    vswitchIds.remove(0);
            }
            // 分配公网
            createInstanceTaskHandler.allocatePublicIpAddressHandler(queryTaskMember(taskId,
                    TASK_STATUS_CREATE_INSTANCE), createCloudInstanceBO.getCreateCloudInstance().getAllocatePublicIpAddress());
            // 查询实例详情并录入ip
            // 启动实例
            createInstanceTaskHandler.startInstanceHandler(queryTaskMember(taskId,
                    TASK_STATUS_ALLOCATE_PUBLIC_IP_ADDRESS));
            // 查询实例是否正常运行
            createInstanceTaskHandler.describeInstanceStatusHandler(createCloudInstanceBO.getCloudInstanceTemplate().getRegionId(), queryTaskMember(taskId,
                    TASK_STATUS_STARTING));

            // 校验任务是否完成
            isTaskFinalized = checkTaskCompleted(ocCloudInstanceTask, createCloudInstanceBO);
            // 校验任务是否超时
            if (!isTaskFinalized)
                isTaskFinalized = checkTaskTimeout(ocCloudInstanceTask, taskStartDate);

        }
    }


    // COMPLETED
    private boolean checkTaskCompleted(OcCloudInstanceTask ocCloudInstanceTask, CreateCloudInstanceBO createCloudInstanceBO) {
        List<OcCloudInstanceTaskMember> memberList = queryTaskMember(ocCloudInstanceTask.getId(),
                TASK_STATUS_RUNNING);
        if (memberList.size() == createCloudInstanceBO.getCreateCloudInstance().getCreateSize()) {
            ocCloudInstanceTask.setTaskPhase("FINALIZED");
            ocCloudInstanceTask.setTaskStatus("COMPLETED");
            ocCloudInstanceTaskService.updateOcCloudInstanceTask(ocCloudInstanceTask);
            return true;
        }
        return false;
    }

    private boolean checkTaskTimeout(OcCloudInstanceTask ocCloudInstanceTask, Date taskStartDate) {
        if (TimeUtils.calculateDateAgoMinute(taskStartDate) >= TASK_TIMEOUT_MINUTE) {
            saveCreateInstanceTaskError(ocCloudInstanceTask, "任务超时: > " + TASK_TIMEOUT_MINUTE + "分钟");
            return true;
        }
        return false;
    }


    private List<OcCloudInstanceTaskMember> queryTaskMember(int taskId, String taskStatus) {
        return ocCloudInstanceTaskMemberService.queryOcCloudInstanceTaskMemberByTaskIdAndStatus(taskId, taskStatus);
    }


    // task错误保存
    private void saveCreateInstanceTaskError(OcCloudInstanceTask ocCloudInstanceTask, String errorMsg) {
        ocCloudInstanceTask.setTaskPhase("FINALIZED");
        ocCloudInstanceTask.setTaskStatus("ERROR");
        ocCloudInstanceTask.setErrorMsg(errorMsg);
        ocCloudInstanceTaskService.updateOcCloudInstanceTask(ocCloudInstanceTask);
    }

    // 生成指定长度的轮询虚拟交换机列表，用于创建实例
    private List<String> getVswitchIdList(CreateCloudInstanceBO createCloudInstanceBO) {
        int size = createCloudInstanceBO.getCreateCloudInstance().getCreateSize();
        List<String> vswitchIds = Lists.newArrayList();
        List<OcCloudVpcVswitch> vswitchList;
        // 自动
        if (createCloudInstanceBO.getCreateCloudInstance().getZonePattern().equalsIgnoreCase("auto")) {
            vswitchList = queryVswitchByVpcIdAndZoneIds(createCloudInstanceBO.getCloudInstanceTemplate().getVpcId(), createCloudInstanceBO.getCloudInstanceTemplate().getInstanceZones());
        } else {
            vswitchList = queryVswitchByVpcIdAndVswitchIds(createCloudInstanceBO.getCloudInstanceTemplate().getVpcId(), createCloudInstanceBO.getCreateCloudInstance().getVswitchIds());
            // 单可用区
        }
        while (vswitchIds.size() < size) {
            if (vswitchList.isEmpty()) break;
            for (OcCloudVpcVswitch ocCloudVpcVswitch : vswitchList) {
                if (ocCloudVpcVswitch.getAvailableIpAddressCount() >= 240) {
                    vswitchList.remove(ocCloudVpcVswitch);
                    break;
                } else {
                    ocCloudVpcVswitch.setAvailableIpAddressCount(ocCloudVpcVswitch.getAvailableIpAddressCount() + 1);
                    vswitchIds.add(ocCloudVpcVswitch.getVswitchId());
                }
                if (vswitchIds.size() >= size) break;
            }
        }
        return vswitchIds;
    }


    /**
     * 查询可用的虚拟交换机列表
     *
     * @param vpcId
     * @param instanceZones
     * @return
     */
    private List<OcCloudVpcVswitch> queryVswitchByVpcIdAndZoneIds(String vpcId, List<OcCloudInstanceTemplateVO.InstanceZone> instanceZones) {
        List<String> zoneIds = Lists.newArrayList();
        for (OcCloudInstanceTemplateVO.InstanceZone instanceZone : instanceZones) {
            if (!instanceZone.isActive()) continue;
            zoneIds.add(instanceZone.getZoneId());
        }
        return ocCloudVpcVswitchService.queryOcCloudVpcVswitchByVpcIdAndZoneIds(vpcId, zoneIds);
    }

    /**
     * 按虚拟交换机id查询
     *
     * @param vpcId
     * @param vswitchIds
     * @return
     */
    private List<OcCloudVpcVswitch> queryVswitchByVpcIdAndVswitchIds(String vpcId, List<String> vswitchIds) {
        List<OcCloudVpcVswitch> vswitches = Lists.newArrayList();
        for (String vswitchId : vswitchIds) {
            OcCloudVpcVswitch ocCloudVpcVswitch = ocCloudVpcVswitchService.queryOcCloudVpcVswitchByVswitchId(vswitchId);
            if (ocCloudVpcVswitch != null && vpcId.equals(ocCloudVpcVswitch.getVpcId()))
                vswitches.add(ocCloudVpcVswitch);
        }
        return vswitches;
    }

    @Override
    public OcCloudInstanceTaskVO.CloudInstanceTask queryCloudInstanceTask(int taskId) {
        OcCloudInstanceTask ocCloudInstanceTask = ocCloudInstanceTaskService.queryOcCloudInstanceTaskById(taskId);
        return getCloudInstanceTask(ocCloudInstanceTask);
    }

    @Override
    public OcCloudInstanceTaskVO.CloudInstanceTask queryLastCloudInstanceTask(int templateId) {
        OcCloudInstanceTask ocCloudInstanceTask = ocCloudInstanceTaskService.queryLastOcCloudInstanceTaskByTemplateId(templateId);
        return getCloudInstanceTask(ocCloudInstanceTask);
    }

    private OcCloudInstanceTaskVO.CloudInstanceTask getCloudInstanceTask(OcCloudInstanceTask ocCloudInstanceTask) {
        if (ocCloudInstanceTask == null)
            return new OcCloudInstanceTaskVO.CloudInstanceTask();
        OcCloudInstanceTaskVO.CloudInstanceTask cloudInstanceTask = BeanCopierUtils.copyProperties(ocCloudInstanceTask, OcCloudInstanceTaskVO.CloudInstanceTask.class);
        return cloudInstanceTaskDecorator.decorator(cloudInstanceTask);
    }

}
