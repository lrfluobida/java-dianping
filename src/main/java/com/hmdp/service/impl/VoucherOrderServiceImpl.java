package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    //秒杀优化版
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    //秒杀优化版
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    @PostConstruct
    public void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true){
                try {
                    // 获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 创建订单
                    handlVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }

    //秒杀优化版
    private void handlVoucherOrder(VoucherOrder voucherOrder) {
        // 获取用户Id
        Long userId = voucherOrder.getUserId();
        // 创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 获取锁
        boolean isLock = lock.tryLock();
        if (!isLock) {
            // 获取锁失败，返回错误或者重试
            log.error("不允许重复下单");
            return;
        }
        try {
            proxy.creatVoucherOrder(voucherOrder);

        } finally {
            // 释放锁
            lock.unlock();
        }


    }
    //秒杀优化版 seckillVoucher
    private IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取用户
        Long userId = UserHolder.getUser().getId();
        // 1.执行Lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString());
        // 2.判断是否为0
        int r = result.intValue();
        if (r != 0) {
            // 2.1 不为0，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 2.2 为0，代表有购买资格，把下单信息保存到阻塞队列中
        // 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 生成订单ID
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 当前用户ID
        voucherOrder.setUserId(UserHolder.getUser().getId());
        // 代金券ID
        voucherOrder.setVoucherId(voucherId);
        // 创建阻塞队列
        orderTasks.add(voucherOrder);
        // 3.获取代理对象（实现事务，防止@Transactional失效）
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 4.返回订单ID
        return Result.ok(orderId);
    }
/*    @Override
    public Result seckillVoucher(Long voucherId) {

        // 1.查询优惠券信息
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        // 2.查询秒杀是否开始
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀尚未开始");
        }
        // 3.判断秒杀是否结束
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())){
            // 已经结束
            return Result.fail("秒杀已经结束");
        }
        // 4.判断库存是否充足
        if (seckillVoucher.getStock() < 1) {
            // 库存不足
            return Result.fail("库存不足");
        }
        Long userId = UserHolder.getUser().getId();
        // 保证事务提交后释放锁
        synchronized(userId.toString().intern()) {
            // 获取代理对象（实现事务，防止@Transactional失效）
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.creatVoucherOrder(voucherId);
        }
        // 创建分布式锁对象
        //SimpleRedisLock redisLock = new SimpleRedisLock("order" + userId, stringRedisTemplate);
        // 使用Redisson 实现分布式锁
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 获取锁
        boolean islock = lock.tryLock();
        if (!islock) {
            // 获取锁失败
            return Result.fail("请勿重复下单");
        }

        // 获取代理对象（实现事务，防止@Transactional失效）
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.creatVoucherOrder(voucherId);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }*/

/*    @Transactional
    public @NonNull  Result creatVoucherOrder(Long voucherId) {
        // 实现一人一单
        Long userId = UserHolder.getUser().getId();


            // 查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            // 判断是否存在
            if (count > 0) {
                // 用户已经购买过
                return Result.fail("用户已经购买过");
            }
            // 5.扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1")// 库存-1
                    .eq("voucher_id", voucherId).gt("stock", 0)//where id=? and stock>0
                    .update();
            if (!success) {
                // 扣减库存失败
                return Result.fail("库存不足");
            }
            // 6.创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            // 6.1.生成订单ID
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            // 6.2.当前用户ID
            voucherOrder.setUserId(UserHolder.getUser().getId());
            // 6.3.代金券ID
            voucherOrder.setVoucherId(voucherId);
            // 6.4.保存订单到数据库
            save(voucherOrder);
            return Result.ok(orderId);
        }*/

    //秒杀优化版创建订单
    @Transactional
    public void creatVoucherOrder(VoucherOrder voucherOrder) {
        // 获取用户Id
        Long userId = voucherOrder.getUserId();
        // 查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 判断是否存在
        if (count > 0) {
            // 用户已经购买过
            log.error("用户已经购买过");
            return;
        }
        // 5.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")// 库存-1
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)//where id=? and stock>0
                .update();
        if (!success) {
            // 扣减库存失败
            log.error("库存不足");
            return;
        }

        // 6.4.保存订单到数据库
        save(voucherOrder);

    }




}
