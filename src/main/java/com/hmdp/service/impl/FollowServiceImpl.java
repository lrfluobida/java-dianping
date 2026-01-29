package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;
    @Override
    public Result follow(Long followUserid, Boolean isFollow) {
        // 获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        // 判断是关注还是取关
        if(isFollow) {
            // 关注
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserid);
            boolean isSuccess = save(follow);
            if(isSuccess) {
                // 将关注用户id放入redis的set集合 sadd follows:userId followUserid
                stringRedisTemplate.opsForSet().add(key, followUserid.toString());
            }
        } else {
            // 取关,删除关注记录 delete from tb_follow where user_id = ? and follow_user_id = ?
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", followUserid));
            if(isSuccess) {
                // 将关注用户id从redis的set集合中移除 srem follows:userId followUserid
                stringRedisTemplate.opsForSet().remove(key, followUserid.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserid) {
        // 获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 查询是否关注 select count(*) from tb_follow where user_id = ? and follow_user
        Integer count = query().eq("user_id", userId)
                .eq("follow_user_id", followUserid).count();
        // 返回是否关注
        return Result.ok(count > 0);

    }

    @Override
    public Result followCommons(Long id) {
        // 获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key1 = "follows:" + userId;
        String key2 = "follows:" + id;
        // 求交集 sinter follows:userId follows:id
        // 注意：这里返回的是字符串集合，需要转换为Long类型集合
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if (intersect == null || intersect.isEmpty()) {
            // 无交集，返回空列表
            return Result.ok(Collections.emptyList());
        }
        // 有交集，解析id列表
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        // 查询用户信息 select * from tb_user where id in (ids)
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        // 返回用户信息
        return Result.ok(users);
    }
}
