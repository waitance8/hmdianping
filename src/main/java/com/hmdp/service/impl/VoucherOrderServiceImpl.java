package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

   @Resource
   private ISeckillVoucherService seckillVoucherService;

   @Resource
   private RedisIdWorker redisIdWorker;
   @Resource
   private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.ok("秒杀尚未开始！");
        }
        // 3判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.ok("秒杀已经结束！");
        }
        // 4判断库存是否充足
        if (voucher.getStock() < 1){
            return Result.fail("库存不足！");
        }

        Long userId = UserHolder.getUser().getId();
        // 创建锁对象
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        // 获取锁
        boolean isLock = lock.tryLock(1200);
        // 判断是否获取锁成功
        if(!isLock){
            // 获取锁失败
            return Result.fail("不允许重复下单");
        }
        try {
        // 获取代理对象（事务）
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 5一人一单
        Long userId = UserHolder.getUser().getId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if(count>0){
            return Result.fail("用户已经购买过一次！");
        }
        // 6扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();
        if(!success){
            return Result.fail("库存不足");
        }
        // 6创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //  订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //  用户id
        voucherOrder.setUserId(userId);
        //  代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        // 返回订单id
        return Result.ok(orderId);
    }
}
