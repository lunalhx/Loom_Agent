package cn.lunalhx.ai.infrastructure.dao;

import cn.lunalhx.ai.infrastructure.dao.po.ModelCallLogPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ModelCallLogDao {

    int insert(ModelCallLogPO modelCallLog);

}
