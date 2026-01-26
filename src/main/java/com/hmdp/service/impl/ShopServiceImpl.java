package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        // 解决缓存穿透
        Shop shop = queryWithPassThrough(id);
        // 利用工具类封装解决缓存穿透
        //Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class,this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 用互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);
        //逻辑过期解决缓存击穿
        //Shop shop = queryWithLogicalExpire(id);
        // 利用工具类封装逻辑过期解决缓存击穿
//        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class,this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        if (shop == null) {
//            return Result.fail("店铺不存在");
//        }

        // 7.返回
        return Result.ok(shop);
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

    // 互斥锁防止缓存击穿
    public Shop queryWithMutex(Long id) {
        // 1.从redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);

        }
        // 判断是否是空值（空字符串）
        /*
         * StrUtil.isNotBlank(null) -> false
         * StrUtil.isNotBlank("") -> false
         * StrUtil.isNotBlank("\t\n") -> false
         * StrUtil.isNotBlank("abd") -> true
         * 所以经过上面判断之后下面只要判断是不是null，如果不是则代表是空值""（即判断shopJson == "\t\n"和shopJson == "")
         * shopJson == null意味着redis里没有可以去数据库查，如果!=null则shopJson == ""空值意味着数据库里面也没有不用再去查了
         * */
        if (shopJson != null) {
            return null;
        }
        // 实现缓存重建
        // 4.1.获取互斥锁
        String lock = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lock);
            // 4.2.判断是否获取成功
            if (!isLock) {
                // 4.3.失败，则休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 4.4 成功则根据id查询数据库
            // 先再次检测redis缓存是否存在，如果存在则无需重建直接返回
            shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
            if (StrUtil.isNotBlank(shopJson)) {
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            if (shopJson != null) {
                return null;
            }
            // 确定不存在，则进行重建
            //Thread.sleep(200);// 模拟重建延时
            shop = getById(id);

            // 5.不存在，返回错误
            if (shop == null) {
                // 将空值（空字符串）写入redis防止缓存穿透
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                // 返回错误
                return null;
            }
            // 6.存在，写入redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            // 释放锁
            unLock(lock);
        }

        // 7.返回
        return shop;
    }

    /**
     * 缓存重建线程池
     */
    private  static final ExecutorService CACHE_REBUILD_EXCUTOR = Executors.newFixedThreadPool(10);
    // 逻辑过期解决缓存击穿
    public Shop queryWithLogicalExpire(Long id) {
        // 1.从redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 2.判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            // 3.不存在，直接返回
            return null;

        }
        // 命中，需要先把json反序列化为对象
        RedisData redisdata = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisdata.getData(), Shop.class);
        LocalDateTime expireTime = redisdata.getExpireTime();
        // 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 未过期则返回商铺信息
            return shop;
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
                    // 重建缓存
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    // 释放锁
                    unLock(lock);
                }

            });

        }


        // 7.返回过期商铺信息（无论获取锁成功还是失败都要返回该信息）
        return shop;
    }
    /*
    * 从缓存中获取商铺信息
    * @param key
    * @return
    * */




    // 防止缓存穿透
    public Shop queryWithPassThrough(Long id) {
        // 1.从redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);

        }
        // 判断是否是空值（空字符串）
        /*
         * StrUtil.isNotBlank(null) -> false
         * StrUtil.isNotBlank("") -> false
         * StrUtil.isNotBlank("\t\n") -> false
         * StrUtil.isNotBlank("abd") -> true
         * 所以经过上面判断之后下面只要判断是不是null，如果不是则代表是空值""（即判断shopJson == "\t\n"和shopJson == "")
         * shopJson == null意味着redis里没有可以去数据库查，如果!=null则shopJson == ""空值意味着数据库里面也没有不用再去查了
         * */
        if (shopJson != null) {
            return null;
        }

        // 4.不存在，根据id查询数据库
        Shop shop = getById(id);

        // 5.不存在，返回错误
        if (shop == null) {
            // 将空值（空字符串）写入redis防止缓存穿透
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 返回错误
            return null;
        }
        // 6.存在，写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 7.返回
        return shop;
    }


    /**
     * 将数据保存到缓存中
     *
     * @param id            商铺id
     * @param expireSeconds 逻辑过期时间
     */
    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        // 1.查询店铺数据
        Shop shop = getById(id);
        //Thread.sleep(200);// 模拟重建延时
        // 2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3.写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }
    @Override
    @Transactional
    public Result update(Shop shop) {
        if(shop.getId()== null){
            return Result.fail("店铺id不能为空");
        }
        // 1. 更新数据库
        updateById(shop);
        // 2. 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }
}
