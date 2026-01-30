package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    /**
     * 发送手机验证码
     *
     * @param phone 手机号
     * @return 验证码发送结果
     */
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误
            return Result.fail("手机号格式错误！");
        }
        // 3.如果符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 4.保存验证码到redis,并设置有效期
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 5.发送验证码
        log.debug("发送短信验证码成功，验证码：{}", code);

        // 6.返回结果
        return Result.ok();
    }

    /**
     * 登录功能
     *
     * @param loginForm 登录参数，包含手机号、验证码;
     * @return 登录结果
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误
            return Result.fail("手机号格式错误！");
        }
        // 3.校验验证码
        String cachecode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if (cachecode == null || !cachecode.equals(code)) {
            // 3.不一致，返回错误
            return Result.fail("验证码错误！");
        }
        // 4.一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();

        // 5.判断用户是否存在
        if (user == null) {
            // 6.不存在，创建用户信息并保存到数据库
            user = creatUserWithPhone(phone);
        }

        // 7.存在，保存用户信息到redis中
        // 7.1 生成token，随机生成，作为登录凭证
        String token = UUID.randomUUID().toString(true);
        // 7.2 将userDTO转为hashmap存储
        // 将对象中字段全部转成string类型，StringRedisTemplate只能存字符串类型的数据
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((key, value) -> value.toString())
                        );

        // 7.3 存储
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);
        // 7.4 设置token有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        // 获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 获取当前日期
        LocalDateTime now = LocalDateTime.now();
        // 拼接key
        String key = USER_SIGN_KEY + userId + ":" + now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        // 获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 写入Redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();

    }

    @Override
    public Result signCount() {
        // 获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 获取当前日期
        LocalDateTime now = LocalDateTime.now();
        // 拼接key
        String key = USER_SIGN_KEY + userId + ":" + now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        // 获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 获取本月截至今天的签到记录，返回的是一个十进制的数字 BITFIELD sign:userid:yyyyMM GET u<dayOfMonth> 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                // 从第0位开始，获取dayOfMonth位
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if(result == null || result.isEmpty()){
            return Result.ok(0);
        }
        Long num = result.get(0);
        if(num == null || num == 0){
            return Result.ok(0);
        }
        int count = 0;
        // 循环 dayOfMonth 次，依次判断每一天的签到情况
        while (true){
            // 让这个数字与1做与运算，得到数字的最后一位
            if((num & 1) == 0){
                // 因为统计的是截至今天为止的连续签到天数（即从今天往前连续签到多少天，即从今天开始往前有多少个连续的1）
                // 如果为0，说明该天未签到，结束
                break;
            }else {
                // 如果为1，说明已签到，计数器+1
                count++;
            }
            // 把数字右移一位，抛弃最后一位，继续下一个bit位的判断
            num >>>= 1;
        }

        return Result.ok(count);
    }

    private User creatUserWithPhone(String phone) {
        // 创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 保存用户
        save(user);
        return user;
    }
}
