package com.example.inventory.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** MyBatis-Plus mapper for the reservation header. */
@Mapper
public interface ReservationMapper extends BaseMapper<ReservationDo> {}
