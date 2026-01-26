package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.NonNull;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Override

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
    }

    @Transactional
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
        }

}
