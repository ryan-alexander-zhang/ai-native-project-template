package com.example.inventory.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** MyBatis-Plus mapper for reservation lines. */
@Mapper
public interface ReservationLineMapper extends BaseMapper<ReservationLineDo> {
}
