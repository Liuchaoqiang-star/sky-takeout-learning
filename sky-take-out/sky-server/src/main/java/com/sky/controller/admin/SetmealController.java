package com.sky.controller.admin;


import com.sky.dto.SetmealDTO;
import com.sky.dto.SetmealPageQueryDTO;
import com.sky.entity.SetmealDish;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.SetmealService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController//1. 必须加，标识这是一个控制器并返回JSON
@RequestMapping("/admin/setmeal")//2.类级别路径映射，这一块所有的接口都以这个开头
@Api(tags = "套餐相关接口")//3.用于Swagger生成文档
public class SetmealController {
    @Autowired
    private SetmealService setmealService;



    @PostMapping   //添加套餐
    @CacheEvict(cacheNames = "setmealCache", allEntries = true)
    public Result save(@RequestBody SetmealDTO setmealDTO) {
        log.info("添加套餐");
        setmealService.saveWithDish(setmealDTO);
        return Result.success();
    }


    @GetMapping("/page")
    @ApiOperation("进行分也查询套餐")
    public Result<PageResult> page(SetmealPageQueryDTO setmealPageQueryDTO) {
        // 1. 打印日志（老习惯了，方便出 bug 找原因）
        log.info("分页查询套餐：{}",setmealPageQueryDTO);
        // 2. 调用 service 执行分页查询
        PageResult pageResult =setmealService.pageQuery(setmealPageQueryDTO);
        // 3. 返回 Result.success(pageResult)
        return Result.success(pageResult);
    }

    @DeleteMapping()
    @ApiOperation("批量删除套餐")
    @CacheEvict(cacheNames = "setmealCache", allEntries = true)
    public Result delete(@RequestParam List<Long> ids){
        log.info("批量删除套餐{}",ids);
        setmealService.deleteBatch(ids);
        return Result.success();
    }



    @PutMapping
    @ApiOperation("修改套餐")
    @CacheEvict(cacheNames = "setmealCache", allEntries = true)
    public Result update(@RequestBody SetmealDTO setmealDTO) {
        log.info("修改套餐：{}", setmealDTO);
        setmealService.updateWithDish(setmealDTO); // 这里的名字你可以自己起
        return Result.success();
    }

    @PostMapping("/status/{status}")
    @ApiOperation("套餐起售停售")
    @CacheEvict(cacheNames = "setmealCache", allEntries = true)
    public Result startOrStop(@PathVariable Integer status,Long id){
        log.info("套餐起售、停售: {}，{}", status,id);
        setmealService.startOrStop(status, id);
        return Result.success();

    }








}
