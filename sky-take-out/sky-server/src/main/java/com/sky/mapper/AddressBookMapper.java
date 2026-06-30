package com.sky.mapper;

import com.sky.entity.AddressBook;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AddressBookMapper {

    /**
     * 条件查询
     */
    List<AddressBook> list(AddressBook addressBook);

    /**
     * 新增地址
     */
    @Insert("insert into address_book " +
            "(user_id, consignee, phone, sex, province_code, province_name, city_code, city_name, district_code, " +
            "district_name, detail, label, is_default) " +
            "values (#{userId}, #{consignee}, #{phone}, #{sex}, #{provinceCode}, #{provinceName}, #{cityCode}, #{cityName}, " +
            "#{districtCode}, #{districtName}, #{detail}, #{label}, #{isDefault})")
    void insert(AddressBook addressBook);

    /**
     * 根据id查询地址
     */
    @Select("select * from address_book where id = #{id}")
    AddressBook getById(Long id);

    /**
     * 根据id修改地址
     */
    void update(AddressBook addressBook);

    /**
     * 根据用户id修改是否默认地址
     */
    @Update("update address_book set is_default = #{isDefault} where user_id = #{userId}")
    void updateIsDefaultByUserId(AddressBook addressBook);

    /**
     * 根据id删除地址
     */
    @Delete("delete from address_book where id = #{id}")
    void deleteById(Long id);
}
