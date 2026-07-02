package com.sky.service.impl;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.context.BaseContext;
import com.sky.dto.OrdersCancelDTO;
import com.sky.dto.OrdersConfirmDTO;
import com.sky.dto.OrdersPageQueryDTO;
import com.sky.dto.OrdersPaymentDTO;
import com.sky.dto.OrdersRejectionDTO;
import com.sky.dto.OrdersSubmitDTO;
import com.sky.entity.AddressBook;
import com.sky.entity.OrderDetail;
import com.sky.entity.Orders;
import com.sky.entity.ShoppingCart;
import com.sky.entity.User;
import com.sky.exception.AddressBookBusinessException;
import com.sky.exception.OrderBusinessException;
import com.sky.exception.ShoppingCartBusinessException;
import com.sky.mapper.AddressBookMapper;
import com.sky.mapper.OrderDetailMapper;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.ShoppingCartMapper;
import com.sky.mapper.UserMapper;
import com.sky.result.PageResult;
import com.sky.service.OrderService;
import com.sky.utils.HttpClientUtil;
import com.sky.utils.WeChatPayUtil;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderStatisticsVO;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.OrderVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class OrderServiceImpl implements OrderService {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderDetailMapper orderDetailMapper;

    @Autowired
    private ShoppingCartMapper shoppingCartMapper;

    @Autowired
    private AddressBookMapper addressBookMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private WeChatPayUtil weChatPayUtil;

    @Value("${sky.shop.address:}")
    private String shopAddress;

    @Value("${sky.baidu.ak:}")
    private String baiduAk;

    /**
     * 用户下单
     */
    @Override
    @Transactional
    public OrderSubmitVO submitOrder(OrdersSubmitDTO ordersSubmitDTO) {
        Long userId = BaseContext.getCurrentId();

        // 1. 根据前端传来的地址id查询收货地址，没有地址就不能下单
        AddressBook addressBook = addressBookMapper.getById(ordersSubmitDTO.getAddressBookId());
        if (addressBook == null) {
            throw new AddressBookBusinessException(MessageConstant.ADDRESS_BOOK_IS_NULL);
        }
        String fullAddress = buildFullAddress(addressBook);

        // 下单前先校验配送范围，超过5公里就不再创建订单
        checkOutOfRange(fullAddress);

        // 2. 查询当前登录用户的购物车，下单商品以后端购物车为准
        ShoppingCart shoppingCart = ShoppingCart.builder()
                .userId(userId)
                .build();
        List<ShoppingCart> shoppingCartList = shoppingCartMapper.list(shoppingCart);
        if (shoppingCartList == null || shoppingCartList.isEmpty()) {
            throw new ShoppingCartBusinessException(MessageConstant.SHOPPING_CART_IS_NULL);
        }

        // 3. 组装订单主表数据：一笔订单整体只有一份的信息放到orders表
        Orders orders = new Orders();
        BeanUtils.copyProperties(ordersSubmitDTO, orders);
        orders.setNumber(String.valueOf(System.currentTimeMillis()));
        orders.setStatus(Orders.PENDING_PAYMENT);
        orders.setUserId(userId);
        orders.setAddressBookId(addressBook.getId());
        orders.setOrderTime(LocalDateTime.now());
        orders.setPayStatus(Orders.UN_PAID);
        orders.setPhone(addressBook.getPhone());
        orders.setConsignee(addressBook.getConsignee());
        orders.setAddress(fullAddress);

        // 4. 插入订单主表，useGeneratedKeys会把数据库生成的id回填到orders.id
        orderMapper.insert(orders);

        // 5. 组装订单明细：购物车里的每一行商品，都会变成一条order_detail
        List<OrderDetail> orderDetailList = new ArrayList<>();
        for (ShoppingCart cart : shoppingCartList) {
            OrderDetail orderDetail = new OrderDetail();
            BeanUtils.copyProperties(cart, orderDetail);
            orderDetail.setOrderId(orders.getId());
            orderDetailList.add(orderDetail);
        }
        orderDetailMapper.insertBatch(orderDetailList);

        // 6. 下单成功后清空当前用户购物车
        shoppingCartMapper.deleteByUserId(userId);

        // 7. 返回前端需要展示的下单结果
        return OrderSubmitVO.builder()
                .id(orders.getId())
                .orderNumber(orders.getNumber())
                .orderAmount(orders.getAmount())
                .orderTime(orders.getOrderTime())
                .build();
    }

    /**
     * 订单支付：向微信创建预支付订单，并把调起支付需要的参数返回给小程序
     */
    @Override
    public OrderPaymentVO payment(OrdersPaymentDTO ordersPaymentDTO) throws Exception {
        Long userId = BaseContext.getCurrentId();
        User user = userMapper.getById(userId);
        Orders orders = orderMapper.getByNumber(ordersPaymentDTO.getOrderNumber());

        if (orders == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        if (!orders.getUserId().equals(userId)) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        // 金额以后端订单表为准，不能相信前端传来的金额
        JSONObject jsonObject = weChatPayUtil.pay(
                orders.getNumber(),
                orders.getAmount(),
                "苍穹外卖订单",
                user.getOpenid()
        );

        if ("ORDERPAID".equals(jsonObject.getString("code"))) {
            throw new OrderBusinessException("该订单已支付");
        }

        OrderPaymentVO vo = jsonObject.toJavaObject(OrderPaymentVO.class);
        vo.setPackageStr(jsonObject.getString("package"));
        return vo;
    }

    /**
     * 支付成功后，微信会回调/notify/paySuccess。
     * 这里根据微信回调里的商户订单号，把订单改成已支付、待接单。
     */
    @Override
    public void paySuccess(String outTradeNo) {
        Orders ordersDB = orderMapper.getByNumber(outTradeNo);
        if (ordersDB == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        Orders orders = Orders.builder()
                .id(ordersDB.getId())
                .status(Orders.TO_BE_CONFIRMED)
                .payStatus(Orders.PAID)
                .checkoutTime(LocalDateTime.now())
                .build();

        orderMapper.update(orders);
    }

    /**
     * 用户端订单分页查询
     */
    @Override
    public PageResult pageQuery4User(int pageNum, int pageSize, Integer status) {
        // 1. 开启分页：它会拦截紧跟着的那一次MyBatis查询，自动加limit
        PageHelper.startPage(pageNum, pageSize);

        // 2. 组装查询条件：用户只能查自己的订单，status可传可不传
        OrdersPageQueryDTO ordersPageQueryDTO = new OrdersPageQueryDTO();
        ordersPageQueryDTO.setUserId(BaseContext.getCurrentId());
        ordersPageQueryDTO.setStatus(status);

        // 3. 分页查询订单主表orders
        Page<Orders> page = orderMapper.pageQuery(ordersPageQueryDTO);

        // 4. 每个订单再查对应的订单明细，封装成前端需要的OrderVO
        List<OrderVO> list = new ArrayList<>();
        if (page != null && page.getTotal() > 0) {
            for (Orders orders : page) {
                List<OrderDetail> orderDetails = orderDetailMapper.getByOrderId(orders.getId());

                OrderVO orderVO = new OrderVO();
                BeanUtils.copyProperties(orders, orderVO);
                orderVO.setOrderDetailList(orderDetails);
                list.add(orderVO);
            }
        }

        return new PageResult(page.getTotal(), list);
    }

    /**
     * 查询订单详情
     */
    @Override
    public OrderVO details(Long id) {
        Orders orders = orderMapper.getById(id);
        if (orders == null || !orders.getUserId().equals(BaseContext.getCurrentId())) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(id);

        OrderVO orderVO = new OrderVO();
        BeanUtils.copyProperties(orders, orderVO);
        orderVO.setOrderDetailList(orderDetailList);
        return orderVO;
    }

    /**
     * 用户取消订单
     */
    @Override
    public void userCancelById(Long id) throws Exception {
        Orders ordersDB = orderMapper.getById(id);
        if (ordersDB == null || !ordersDB.getUserId().equals(BaseContext.getCurrentId())) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        // 用户端只允许取消待付款、待接单订单；已接单/派送中要联系商家处理
        if (ordersDB.getStatus() > Orders.TO_BE_CONFIRMED) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Orders orders = new Orders();
        orders.setId(ordersDB.getId());

        // 待接单说明已经支付成功了，取消时需要走微信退款
        if (ordersDB.getStatus().equals(Orders.TO_BE_CONFIRMED)) {
            weChatPayUtil.refund(
                    ordersDB.getNumber(),
                    ordersDB.getNumber(),
                    ordersDB.getAmount(),
                    ordersDB.getAmount()
            );
            orders.setPayStatus(Orders.REFUND);
        }

        orders.setStatus(Orders.CANCELLED);
        orders.setCancelReason("用户取消");
        orders.setCancelTime(LocalDateTime.now());
        orderMapper.update(orders);
    }

    /**
     * 再来一单
     */
    @Override
    public void repetition(Long id) {
        Long userId = BaseContext.getCurrentId();
        Orders orders = orderMapper.getById(id);
        if (orders == null || !orders.getUserId().equals(userId)) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(id);
        List<ShoppingCart> shoppingCartList = orderDetailList.stream().map(orderDetail -> {
            ShoppingCart shoppingCart = new ShoppingCart();
            BeanUtils.copyProperties(orderDetail, shoppingCart, "id");
            shoppingCart.setUserId(userId);
            shoppingCart.setCreateTime(LocalDateTime.now());
            return shoppingCart;
        }).collect(Collectors.toList());

        shoppingCartMapper.insertBatch(shoppingCartList);
    }

    /**
     * 管理端订单条件搜索。
     * 管理端不设置userId，所以可以查所有用户的订单；其他筛选条件由前端传入DTO。
     */
    @Override
    public PageResult conditionSearch(OrdersPageQueryDTO ordersPageQueryDTO) {
        // PageHelper只对紧跟着的第一次MyBatis查询生效
        PageHelper.startPage(ordersPageQueryDTO.getPage(), ordersPageQueryDTO.getPageSize());

        // 复用订单分页SQL：number、phone、status、beginTime、endTime有值才会参与筛选
        Page<Orders> page = orderMapper.pageQuery(ordersPageQueryDTO);

        // 管理端列表还要展示“菜品*数量;”这种摘要，所以把Orders转换成OrderVO
        List<OrderVO> orderVOList = getOrderVOList(page);
        return new PageResult(page.getTotal(), orderVOList);
    }

    /**
     * 把订单主表列表转换成管理端展示用的VO列表。
     */
    private List<OrderVO> getOrderVOList(Page<Orders> page) {
        List<OrderVO> orderVOList = new ArrayList<>();
        List<Orders> ordersList = page.getResult();

        if (ordersList != null && !ordersList.isEmpty()) {
            for (Orders orders : ordersList) {
                OrderVO orderVO = new OrderVO();
                BeanUtils.copyProperties(orders, orderVO);

                // orderDishes不是orders表字段，需要从order_detail明细表拼出来
                String orderDishes = getOrderDishesStr(orders);
                orderVO.setOrderDishes(orderDishes);

                orderVOList.add(orderVO);
            }
        }

        return orderVOList;
    }

    /**
     * 根据订单明细拼接菜品摘要，例如：宫保鸡丁*2;米饭*1;
     */
    private String getOrderDishesStr(Orders orders) {
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(orders.getId());
        List<String> orderDishList = orderDetailList.stream().map(orderDetail ->
                orderDetail.getName() + "*" + orderDetail.getNumber() + ";"
        ).collect(Collectors.toList());

        return String.join("", orderDishList);
    }

    /**
     * 各个状态的订单数量统计。
     * 只统计orders主表的状态，不需要查订单明细表。
     */
    @Override
    public OrderStatisticsVO statistics() {
        // 待接单：用户已支付，等待商家处理
        Integer toBeConfirmed = orderMapper.countStatus(Orders.TO_BE_CONFIRMED);
        // 待派送：商家已接单，等待配送
        Integer confirmed = orderMapper.countStatus(Orders.CONFIRMED);
        // 派送中：订单已经开始配送
        Integer deliveryInProgress = orderMapper.countStatus(Orders.DELIVERY_IN_PROGRESS);

        OrderStatisticsVO orderStatisticsVO = new OrderStatisticsVO();
        orderStatisticsVO.setToBeConfirmed(toBeConfirmed);
        orderStatisticsVO.setConfirmed(confirmed);
        orderStatisticsVO.setDeliveryInProgress(deliveryInProgress);
        return orderStatisticsVO;
    }

    /**
     * 管理端查询订单详情。
     * 和用户端详情一样都要查主表和明细表，但管理端可以查看所有用户订单，不做userId校验。
     */
    @Override
    public OrderVO adminDetails(Long id) {
        Orders orders = orderMapper.getById(id);
        if (orders == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        // 订单详情页需要展示这一单买了哪些菜/套餐
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(id);

        OrderVO orderVO = new OrderVO();
        // 先把订单主表字段复制到VO，比如订单号、状态、金额、地址等
        BeanUtils.copyProperties(orders, orderVO);
        // 再把明细列表放进去，这样前端既能看到订单整体，也能看到商品明细
        orderVO.setOrderDetailList(orderDetailList);
        return orderVO;
    }

    /**
     * 接单。
     * 只有“待接单”的订单才能被商家接单，接单后状态变为“已接单/待派送”。
     */
    @Override
    public void confirm(OrdersConfirmDTO ordersConfirmDTO) {
        Orders ordersDB = orderMapper.getById(ordersConfirmDTO.getId());
        if (ordersDB == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        if (!ordersDB.getStatus().equals(Orders.TO_BE_CONFIRMED)) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Orders orders = new Orders();
        orders.setId(ordersDB.getId());
        // 后端固定设置为已接单，不使用前端传来的status，避免前端乱传状态
        orders.setStatus(Orders.CONFIRMED);
        orderMapper.update(orders);
    }

    /**
     * 派送订单。
     * 只有“已接单/待派送”的订单才能开始派送，状态从3变成4。
     */
    @Override
    public void delivery(Long id) {
        Orders ordersDB = orderMapper.getById(id);
        if (ordersDB == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        // 订单必须先被商家接单，才能进入派送中
        if (!ordersDB.getStatus().equals(Orders.CONFIRMED)) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Orders orders = new Orders();
        orders.setId(ordersDB.getId());
        orders.setStatus(Orders.DELIVERY_IN_PROGRESS);
        orderMapper.update(orders);
    }

    /**
     * 完成订单。
     * 只有“派送中”的订单才能完成，完成时记录实际送达时间。
     */
    @Override
    public void complete(Long id) {
        Orders ordersDB = orderMapper.getById(id);
        if (ordersDB == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        // 订单必须已经进入派送中，才能被标记为已完成
        if (!ordersDB.getStatus().equals(Orders.DELIVERY_IN_PROGRESS)) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Orders orders = new Orders();
        orders.setId(ordersDB.getId());
        orders.setStatus(Orders.COMPLETED);
        orders.setDeliveryTime(LocalDateTime.now());
        orderMapper.update(orders);
    }

    /**
     * 拒单。
     * 只有“待接单”的订单才能拒单；如果用户已经支付，需要先发起退款，再把订单改成已取消。
     */
    @Override
    public void rejection(OrdersRejectionDTO ordersRejectionDTO) throws Exception {
        Orders ordersDB = orderMapper.getById(ordersRejectionDTO.getId());
        if (ordersDB == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        // 拒单只能发生在商家还没接单之前，不能拒绝已接单、派送中、已完成的订单
        if (!ordersDB.getStatus().equals(Orders.TO_BE_CONFIRMED)) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Orders orders = new Orders();
        orders.setId(ordersDB.getId());

        // 待接单订单通常已经支付，已支付的订单拒单时要原路退款
        if (Orders.PAID.equals(ordersDB.getPayStatus())) {
            weChatPayUtil.refund(
                    ordersDB.getNumber(),
                    ordersDB.getNumber(),
                    ordersDB.getAmount(),
                    ordersDB.getAmount()
            );
            orders.setPayStatus(Orders.REFUND);
        }

        orders.setStatus(Orders.CANCELLED);
        orders.setRejectionReason(ordersRejectionDTO.getRejectionReason());
        orders.setCancelTime(LocalDateTime.now());
        orderMapper.update(orders);
    }

    /**
     * 商家取消订单。
     * 管理端取消时要保存取消原因；如果用户已经付款，需要先退款再修改订单支付状态。
     */
    @Override
    public void cancel(OrdersCancelDTO ordersCancelDTO) throws Exception {
        Orders ordersDB = orderMapper.getById(ordersCancelDTO.getId());
        if (ordersDB == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        // 已完成、已取消的订单不能再取消，避免重复取消或破坏历史订单
        if (ordersDB.getStatus().equals(Orders.COMPLETED) || ordersDB.getStatus().equals(Orders.CANCELLED)) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Orders orders = new Orders();
        orders.setId(ordersDB.getId());

        // 已支付订单取消时要退款；未支付订单只需要改订单状态
        if (Orders.PAID.equals(ordersDB.getPayStatus())) {
            String refundResult = weChatPayUtil.refund(
                    ordersDB.getNumber(),
                    ordersDB.getNumber(),
                    ordersDB.getAmount(),
                    ordersDB.getAmount()
            );
            log.info("商家取消订单，申请退款结果：{}", refundResult);
            orders.setPayStatus(Orders.REFUND);
        }

        orders.setStatus(Orders.CANCELLED);
        orders.setCancelReason(ordersCancelDTO.getCancelReason());
        orders.setCancelTime(LocalDateTime.now());
        orderMapper.update(orders);
    }

    /**
     * 检查客户收货地址是否超出配送范围。
     * 思路：店铺地址和用户地址先分别转成经纬度，再用百度路线规划算两点距离。
     */
    private void checkOutOfRange(String address) {
        if (isBlank(shopAddress) || isBlank(baiduAk)) {
            log.warn("未配置店铺地址或百度地图AK，跳过配送范围校验");
            return;
        }

        String shopLngLat = getLngLat(shopAddress, "店铺地址解析失败");
        String userLngLat = getLngLat(address, "收货地址解析失败");

        Map<String, String> map = new HashMap<>();
        map.put("origin", shopLngLat);
        map.put("destination", userLngLat);
        map.put("steps_info", "0");
        map.put("ak", baiduAk);

        String json = HttpClientUtil.doGet("https://api.map.baidu.com/directionlite/v1/driving", map);
        JSONObject jsonObject = JSON.parseObject(json);
        if (jsonObject == null || !"0".equals(jsonObject.getString("status"))) {
            throw new OrderBusinessException("配送路线规划失败");
        }

        JSONArray routes = jsonObject.getJSONObject("result").getJSONArray("routes");
        if (routes == null || routes.isEmpty()) {
            throw new OrderBusinessException("配送路线规划失败");
        }

        Integer distance = routes.getJSONObject(0).getInteger("distance");
        if (distance == null) {
            throw new OrderBusinessException("配送路线规划失败");
        }

        // 百度返回的距离单位是米，超过5000米就是超出配送范围
        if (distance > 5000) {
            throw new OrderBusinessException("超出配送范围");
        }
    }

    /**
     * 把普通地址解析成经纬度，返回格式按百度路线规划要求拼成“纬度,经度”。
     */
    private String getLngLat(String address, String errorMessage) {
        Map<String, String> map = new HashMap<>();
        map.put("address", address);
        map.put("output", "json");
        map.put("ak", baiduAk);

        String json = HttpClientUtil.doGet("https://api.map.baidu.com/geocoding/v3", map);
        JSONObject jsonObject = JSON.parseObject(json);
        if (jsonObject == null || !"0".equals(jsonObject.getString("status"))) {
            throw new OrderBusinessException(errorMessage);
        }

        JSONObject location = jsonObject.getJSONObject("result").getJSONObject("location");
        String lat = location.getString("lat");
        String lng = location.getString("lng");
        return lat + "," + lng;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * 把省市区和详细地址拼成订单快照，后面用户修改地址也不会影响历史订单
     */
    private String buildFullAddress(AddressBook addressBook) {
        StringBuilder address = new StringBuilder();
        appendIfNotNull(address, addressBook.getProvinceName());
        appendIfNotNull(address, addressBook.getCityName());
        appendIfNotNull(address, addressBook.getDistrictName());
        appendIfNotNull(address, addressBook.getDetail());
        return address.toString();
    }

    private void appendIfNotNull(StringBuilder builder, String value) {
        if (value != null) {
            builder.append(value);
        }
    }
}
