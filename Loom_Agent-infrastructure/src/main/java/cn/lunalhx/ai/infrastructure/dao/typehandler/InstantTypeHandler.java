package cn.lunalhx.ai.infrastructure.dao.typehandler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

public class InstantTypeHandler extends BaseTypeHandler<Instant> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Instant parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setString(i, parameter.toString());
    }

    @Override
    public Instant getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public Instant getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public Instant getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private Instant parse(String value) {
        return value == null ? null : Instant.parse(value);
    }
}
