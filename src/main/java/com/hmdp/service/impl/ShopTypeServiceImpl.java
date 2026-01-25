package com.hmdp.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryTypeList() {
        // 1.从redis中查询商铺类型缓存
        String key = CACHE_SHOP_TYPE_KEY;
        String shopTypeJson = stringRedisTemplate.opsForValue().get(key);
        List<ShopType> shopTypeList = null;
        // 2.判断缓存是否命中
        if (StrUtil.isNotBlank(shopTypeJson)){
            // 2.1 缓存命中，直接返回缓存数据
            shopTypeList = JSONUtil.toList(shopTypeJson, ShopType.class);
            return Result.ok(shopTypeList);

        }
        // 2.2 缓存未命中，查询数据库
        shopTypeList = query().orderByAsc("sort").list();
        // 3. 判断数据库中是否存在该数据
        if(CollUtil.isEmpty(shopTypeList)){
            // 3.1 数据库中不存在，返回错误信息
            return Result.fail("没有该数据");
        }

        // 3.2 数据库中存在，写入redis，并返回数据
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shopTypeList), CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
        return Result.ok(shopTypeList);


    }
}
