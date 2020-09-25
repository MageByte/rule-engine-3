package com.engine.web.aspect;


import com.engine.web.annotation.RateLimit;
import com.engine.web.interceptor.AbstractTokenInterceptor;
import com.engine.web.store.entity.RuleEngineUser;
import com.engine.web.util.HttpServletUtils;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.validation.ValidationException;

/**
 * 〈一句话功能简述〉<br>
 * 〈接口级别限流,依赖于redis〉
 *
 * @author 丁乾文
 * @create 2019/9/22
 * @since 1.0.0
 */
@Component
@Aspect
@Slf4j
@Order(-8)
public class RateLimitAspect {

    @Resource
    private RedissonClient redissonClient;

    /**
     * 限流key前缀,防止与其他redis key重复
     */
    private static final String KEY_PRE = "boot_engine_rate_limit_redis_key_pre";

    /**
     * 存在bug，待优化
     *
     * @param joinPoint joinPoint
     * @param rateLimit rateLimit
     * @return Object
     * @throws Throwable Throwable
     */
    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        String key = KEY_PRE;
        switch (rateLimit.type()) {
            case IP:
                key += HttpServletUtils.getRequest().getRemoteAddr();
                break;
            case URL:
                key += HttpServletUtils.getRequest().getRequestURI();
                break;
            case USER:
                RuleEngineUser ruleEngineUser = AbstractTokenInterceptor.USER.get();
                if (ruleEngineUser == null) {
                    throw new RuntimeException("选择根据用户限流,但是并没有获取到用户登录信息!");
                }
                key += ruleEngineUser.getId().toString();
            case URL_IP:
                HttpServletRequest request = HttpServletUtils.getRequest();
                key += request.getRequestURI() + request.getRemoteAddr();
                break;
            default:
                throw new UnsupportedOperationException();
        }
        log.info("执行限流拦截器,限制类型:{},key:{}", rateLimit.type(), key);
        executor(key, rateLimit);
        return joinPoint.proceed();
    }

    /**
     * 限流执行器
     *
     * @param key       redis key
     * @param rateLimit 速率参数
     */
    private void executor(String key, RateLimit rateLimit) {
        //限制时间间隔
        long refreshInterval = rateLimit.refreshInterval();
        //限制时间间隔内可用次数
        long limit = rateLimit.limit();
        //时间单位
        RateIntervalUnit rateIntervalUnit = rateLimit.rateIntervalUnit();
        RRateLimiter rateLimiter = redissonClient.getRateLimiter(key);
        //初始化RateLimiter的状态，并将配置存储到Redis服务器
        if (!rateLimiter.isExists()) {
            boolean trySetRate = rateLimiter.trySetRate(RateType.OVERALL, limit, refreshInterval, rateIntervalUnit);
            log.info("初始化RateLimiter的状态:{}", trySetRate);
        }
        if (!rateLimiter.tryAcquire()) {
            throw new ValidationException("你访问过于频繁,请稍后重试!");
        }
    }
}
