package com.engine.web.aspect;

import cn.hutool.http.Header;
import cn.hutool.http.useragent.UserAgent;
import cn.hutool.http.useragent.UserAgentUtil;
import com.alibaba.fastjson.JSON;
import com.engine.web.annotation.SystemLog;
import com.engine.web.config.rabbit.RabbitQueueConfig;
import com.engine.web.enums.DeletedEnum;
import com.engine.web.interceptor.MDCLogInterceptor;
import com.engine.web.store.entity.RuleEngineSystemLog;
import com.engine.web.util.HttpServletUtils;
import com.engine.web.util.IPUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.Date;

/**
 * 〈一句话功能简述〉<br>
 * 〈如果带有SystemLog注解,持久化操作日志〉
 *
 * @author 丁乾文
 * @create 2019/11/1
 * @since 1.0.0
 */
@Component
@Aspect
public class SystemLogAspect {
    @Resource
    private RabbitTemplate rabbitTemplate;

    @Around("@annotation(systemLog)")
    private Object before(ProceedingJoinPoint joinPoint, SystemLog systemLog) throws Throwable {
        HttpServletRequest request = HttpServletUtils.getRequest();
        String agent = request.getHeader(Header.USER_AGENT.toString());
        UserAgent userAgent = UserAgentUtil.parse(agent);
        //设置日志参数
        RuleEngineSystemLog log = new RuleEngineSystemLog();
        //日志说明
        log.setDescription(systemLog.description());
        //请求开始时间
        log.setCreateTime(new Date());
        //请求用户id
        log.setUserId(null);
        //请求ip地址
        log.setIp(IPUtils.getRequestIp());
        //浏览器
        log.setBrowser(userAgent.getBrowser().toString());
        //浏览器版本
        log.setBrowserVersion(userAgent.getVersion());
        //系统
        log.setSystem(userAgent.getOs().toString());
        //详情
        log.setDetailed(agent);
        //是否为移动平台
        log.setMobile(userAgent.isMobile());
        //请求参数
        log.setAges(JSON.toJSONString(joinPoint.getArgs()));
        //请求url
        log.setRequestUrl(request.getRequestURL().toString());
        // 过滤掉requestId:
        log.setRequestId(MDC.get(MDCLogInterceptor.REQUEST_ID).substring(10));
        try {
            //执行被代理类方法
            Object proceed = joinPoint.proceed();
            log.setReturnValue(JSON.toJSONString(proceed));
            return proceed;
        } catch (Throwable throwable) {
            //与是否触发了异常
            log.setException(throwable.getMessage());
            throw throwable;
        } finally {
            //请求结束时间
            Date endTime = new Date();
            log.setEndTime(endTime);
            //运行时间,就是执行用了多久执行完毕
            Long runningTime = endTime.getTime() - log.getCreateTime().getTime();
            log.setRunningTime(runningTime);
            log.setDeleted(DeletedEnum.ENABLE.getStatus());
            log.setUpdateTime(endTime);
            //对日志持久化,日志使用@Async异步在高并发情况下仍然会出现问题,这里使用消息队列
            rabbitTemplate.convertAndSend(RabbitQueueConfig.SYSTEM_LOG_QUEUE, log);
        }
    }
}
