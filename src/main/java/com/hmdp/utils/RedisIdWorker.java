package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/*生成全局唯一ID
* */
@Component
public class RedisIdWorker {
    /*
    * 开始时间戳
    * */
    private static final long BEGIN_TIMESTAMP = 1767225600L;

    /*
    * 序列号位数
    * */
    private static final int COUNT_BITS = 32;
    private StringRedisTemplate stringRedisTemplate;

    // 构造器注入
    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextId(String keyPrefix) {
        // 生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;
        // 生成序列号
        // 获取当前日期，精确到天来更精确地区分订单
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 自增长
        // 用基本类型因为要计算
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        // 拼接返回
        return timestamp << COUNT_BITS | count;

    }

}
