package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {
    private StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
       // 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从redis中查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 3.存在，直接返回
            return JSONUtil.toBean(json, type);

        }
        // 判断是否是空值（空字符串）
        /*
         * StrUtil.isNotBlank(null) -> false
         * StrUtil.isNotBlank("") -> false
         * StrUtil.isNotBlank("\t\n") -> false
         * StrUtil.isNotBlank("abd") -> true
         * 所以经过上面判断之后下面只要判断是不是null，如果不是则代表是空值""（即判断json == "\t\n"和json == "")
         * json == null意味着redis里没有可以去数据库查，如果!=null则json == ""空值意味着数据库里面也没有不用再去查了
         * */
        if (json != null) {
            return null;
        }

        // 4.不存在，根据id查询数据库
        R r = dbFallback.apply(id);

        // 5.不存在，返回错误
        if (r == null) {
            // 将空值（空字符串）写入redis防止缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 返回错误
            return null;
        }
        // 6.存在，写入redis
        this.set(key, r, time, unit);

        // 7.返回
        return r;
    }

    private  static final ExecutorService CACHE_REBUILD_EXCUTOR = Executors.newFixedThreadPool(10);
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {

        String key = keyPrefix + id;
        // 1.从redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 2.判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            // 3.不存在，直接返回
            return null;

        }
        // 命中，需要先把json反序列化为对象
        RedisData redisdata = JSONUtil.toBean(shopJson, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisdata.getData(), type);
        LocalDateTime expireTime = redisdata.getExpireTime();
        // 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 未过期则返回商铺信息
            return r;
        }

        // 已过期则需要缓存重建
        // 缓存重建
        // 获取互斥锁
        String lock = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lock);
        // 判断是否获取成功
        if (isLock) {
            // 获取锁成功，双检
            // 1.从redis中查询商铺缓存
            shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
            // 2.判断是否存在
            if (StrUtil.isBlank(shopJson)) {
                // 3.不存在，直接返回
                return null;

            }
            // 进行开启独立线程进行重建
            CACHE_REBUILD_EXCUTOR.submit(() -> {
                try {
                    // 查数据库
                    R r1 = dbFallback.apply(id);

                    // 写入redis
                    this.setWithLogicalExpire(key, r1, time, unit);

                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    // 释放锁
                    unLock(lock);
                }

            });

        }


        // 7.返回过期商铺信息（无论获取锁成功还是失败都要返回该信息）
        return r;
    }

    // 尝试获取锁
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10L, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(flag);
    }
    // 释放锁
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }
}
