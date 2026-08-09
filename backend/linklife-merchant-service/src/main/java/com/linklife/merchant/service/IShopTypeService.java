package com.linklife.merchant.service;

import com.linklife.merchant.entity.ShopType;
import com.baomidou.mybatisplus.spring.service.IService;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopTypeService extends IService<ShopType> {

    List<ShopType> queryTypeListCached();
}
