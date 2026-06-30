package com.sky.service.impl;

import com.sky.context.BaseContext;
import com.sky.dto.ShoppingCartDTO;
import com.sky.entity.Dish;
import com.sky.entity.Setmeal;
import com.sky.entity.ShoppingCart;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.mapper.ShoppingCartMapper;
import com.sky.service.ShoppingCartService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class ShoppingCartServiceImpl implements ShoppingCartService {

    @Autowired
    private ShoppingCartMapper shoppingCartMapper;

    @Autowired
    private DishMapper dishMapper;

    @Autowired
    private SetmealMapper setmealMapper;

    /**
     * 添加购物车
     */
    @Override
    public void addShoppingCart(ShoppingCartDTO shoppingCartDTO) {
        ShoppingCart shoppingCart = new ShoppingCart();
        BeanUtils.copyProperties(shoppingCartDTO, shoppingCart);

        // 当前用户id由JWT拦截器解析后放入BaseContext，不需要前端传
        Long userId = BaseContext.getCurrentId();
        shoppingCart.setUserId(userId);

        // 先查购物车中是否已经有同一个商品，同菜品还要区分口味
        List<ShoppingCart> list = shoppingCartMapper.list(shoppingCart);

        if (list != null && list.size() > 0) {
            // 已经存在：数量加1
            ShoppingCart cart = list.get(0);
            cart.setNumber(cart.getNumber() + 1);
            shoppingCartMapper.updateNumberById(cart);
            return;
        }

        // 不存在：补充商品名称、图片、价格，再插入一条购物车记录
        Long dishId = shoppingCartDTO.getDishId();
        if (dishId != null) {
            Dish dish = dishMapper.getById(dishId);
            shoppingCart.setName(dish.getName());
            shoppingCart.setImage(dish.getImage());
            shoppingCart.setAmount(dish.getPrice());
        } else {
            Long setmealId = shoppingCartDTO.getSetmealId();
            Setmeal setmeal = setmealMapper.getById(setmealId);
            shoppingCart.setName(setmeal.getName());
            shoppingCart.setImage(setmeal.getImage());
            shoppingCart.setAmount(setmeal.getPrice());
        }

        shoppingCart.setNumber(1);
        shoppingCart.setCreateTime(LocalDateTime.now());
        shoppingCartMapper.insert(shoppingCart);
    }

    /**
     * 查看购物车
     */
    @Override
    public List<ShoppingCart> showShoppingCart() {
        Long userId = BaseContext.getCurrentId();
        ShoppingCart shoppingCart = ShoppingCart.builder()
                .userId(userId)
                .build();

        return shoppingCartMapper.list(shoppingCart);
    }

    /**
     * 删除购物车中一个商品
     */
    @Override
    public void subShoppingCart(ShoppingCartDTO shoppingCartDTO) {
        ShoppingCart shoppingCart = new ShoppingCart();
        BeanUtils.copyProperties(shoppingCartDTO, shoppingCart);

        // 只允许操作当前登录用户自己的购物车
        shoppingCart.setUserId(BaseContext.getCurrentId());

        // 复用动态SQL，查出当前用户购物车里对应的那一个商品条目
        List<ShoppingCart> list = shoppingCartMapper.list(shoppingCart);

        if (list != null && list.size() > 0) {
            ShoppingCart cartItem = list.get(0);
            Integer number = cartItem.getNumber();

            if (number == 1) {
                // 数量只剩1份时，再减就应该直接删除这一行
                shoppingCartMapper.deleteById(cartItem.getId());
            } else {
                // 数量大于1份时，只把数量减1
                cartItem.setNumber(number - 1);
                shoppingCartMapper.updateNumberById(cartItem);
            }
        }
    }

    /**
     * 清空购物车
     */
    @Override
    public void cleanShoppingCart() {
        Long userId = BaseContext.getCurrentId();
        shoppingCartMapper.deleteByUserId(userId);
    }
}
