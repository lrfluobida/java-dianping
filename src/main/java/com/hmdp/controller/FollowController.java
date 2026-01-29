package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {
    @Resource
    private IFollowService followService;

    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long followUserid, @PathVariable("isFollow") Boolean isFollow) {
    // followUserid: 被关注/取关的用户id
    // isFollow: true-关注，false-取关
        return followService.follow(followUserid, isFollow);

    }
    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long followUserid) {

        return followService.isFollow(followUserid);

    }
    @GetMapping("/common/{id}")
    public Result followCommons(@PathVariable("id") Long id) {
        return followService.followCommons(id);
    }
}
